(ns lens.import
  (:use plumbing.core)
  (:require [clojure.core.async :as async :refer [<!! go-loop]]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [clojure.pprint :refer [pprint]]
            [clojure.tools.namespace.repl :refer [refresh]]
            [schema.core :as s]
            [com.stuartsierra.component :as component]
            [hap-client.core :as hap]
            [lens.study :refer [study-importer]]
            [lens.study-event-def :refer [study-event-def-importer]]
            [lens.form-def :refer [form-def-importer]]
            [lens.form-ref :refer [form-ref-importer]]
            [lens.item-group-def :refer [item-group-def-importer]]
            [lens.item-group-ref :refer [item-group-ref-importer]]
            [lens.item-def :refer [item-def-importer]]
            [lens.item-ref :refer [item-ref-importer]]
            [lens.event-bus :as bus :refer [publish!!]]
            [lens.parse :as p :refer [parse!]]
            [lens.util :refer [<??]]
            [lens.api :as api]))

(defn- assoc-xf [& kvs]
  (map #(apply assoc % kvs)))

(def FormRef
  {:study-id s/Str
   :form-id s/Str})

(s/defn collect-form-ref
  "Collects the ref in refs under [(:study-id ref) :form-refs (:form-id ref)]."
  [refs ref :- FormRef]
  (update-in refs [(:study-id ref) :form-refs (:form-id ref)] #(conj % ref)))

(defn- collect-item-group-ref [refs val]
  (update-in refs [(:study-id val) :item-group-refs (:item-group-id val)] #(conj % val)))

(defn- collect-item-ref [refs val]
  (update-in refs [(:study-id val) :item-refs (:item-id val)] #(conj % val)))

(defn- collect-form-def [defs val]
  (assoc-in defs [(:study-id val) :form-defs (:id (:data val))] val))

(defn- collect-item-group-def [defs val]
  (assoc-in defs [(:study-id val) :item-group-defs (:id (:data val))] val))

(defn- submit-job! [upsert-fn type study val]
  (->> (async/chan 1 (assoc-xf :type type :study-id (:id (:data study))))
       (async/pipe (upsert-fn study val))))

(defn- select-jobs [jobs]
  (cond
    (seq (:study jobs)) (:study jobs)
    (seq (:form jobs)) (:form jobs)
    (seq (:item-group jobs)) (:item-group jobs)
    (seq (:item jobs)) (:item jobs)))

(defn- add-job [jobs type job]
  (-> (update jobs type #(conj (or % #{}) job))
      (update :count inc)))

(defn- add-jobs [jobs type coll]
  (reduce #(add-job %1 type %2) jobs coll))

(defn- remove-job [jobs type job]
  (-> (update jobs type #(disj % job))
      (update :count dec)))

(defn- create-item-group-ref-job [defs ref]
  (let [form-def (get-in defs [:form-defs (:form-def-id ref)])]
    (->> (async/chan 1 (assoc-xf :type :item-group-ref))
         (async/pipe (api/create-item-group-ref! form-def ref)))))

(defn- create-item-ref-job [defs ref]
  (let [item-group-def (get-in defs [:item-group-defs (:item-group-def-id ref)])]
    (->> (async/chan 1 (assoc-xf :type :item-ref))
         (async/pipe (api/create-item-ref! item-group-def ref)))))

(defn- constrain-jobs [jobs n parse-ch]
  (if (< (:count jobs) n) [parse-ch] []))

(defn import! [service-document n parse-ch]
  (go-loop [ports [parse-ch]
            jobs {:count 0}
            study service-document
            defs {}
            refs {}]
    (log/debug "--------------------------------------------------")
    (log/debug "Parent:" (:data study))
    (log/debug "Defs:  " defs)
    (log/debug "Refs:  " refs)
    (log/debug "Jobs:  " (map-vals count (dissoc jobs :count)))
    (log/debug "--------------------------------------------------")
    (let [[val port] (async/alts! (into ports (select-jobs jobs)))]
      (if val
        (if (= parse-ch port)
          (do (log/debug "Parsed" (:type val) (:id val))
              (case (:type val)
                :study
                (let [job (async/pipe (api/upsert-study! study val)
                                      (async/chan 1 (assoc-xf :type :study)))]
                  (recur [] (add-job jobs :study job) nil defs refs))

                :form-ref
                (recur (constrain-jobs jobs n parse-ch) jobs
                       study defs (collect-form-ref refs val))

                :form-def
                (let [job (submit-job! api/upsert-form-def! :form-def study val)]
                  (recur (constrain-jobs jobs n parse-ch)
                         (add-job jobs :form job) study defs refs))

                :item-group-ref
                (recur (constrain-jobs jobs n parse-ch) jobs study defs
                       (collect-item-group-ref refs val))

                :item-group-def
                (let [job (submit-job! api/upsert-item-group-def! :item-group-def
                                       study val)]
                  (recur (constrain-jobs jobs n parse-ch) (add-job jobs :item-group job) study defs refs))

                :item-ref
                (recur (constrain-jobs jobs n parse-ch) jobs study defs (collect-item-ref refs val))

                :item-def
                (let [job (submit-job! api/upsert-item-def! :item-def study val)]
                  (recur (constrain-jobs jobs n parse-ch) (add-job jobs :item job) study defs refs))

                (recur (constrain-jobs jobs n parse-ch) jobs study defs refs)))

          ;; jobs
          (do
            (log/debug "Finished" (:type val) (:id (:data val)))
            (case (:type val)
              :study
              (recur (constrain-jobs jobs n parse-ch) (remove-job jobs :study port) val defs refs)

              :form-def
              (recur (constrain-jobs jobs n parse-ch) (remove-job jobs :form port) study (collect-form-def defs val) refs)

              :item-group-ref
              (recur (constrain-jobs jobs n parse-ch) (remove-job jobs :item-group port) study defs refs)

              :item-group-def
              (let [create-ref (map #(create-item-group-ref-job (defs (:study-id val)) %))
                    ref-vec [(:study-id val) :item-group-refs (:id (:data val))]
                    ref-jobs (eduction create-ref (get-in refs ref-vec))
                    jobs (-> (remove-job jobs :item-group port)
                             (add-jobs :item-group ref-jobs))]
                (recur (constrain-jobs jobs n parse-ch) jobs study (collect-item-group-def defs val) (dissoc-in refs ref-vec)))

              :item-ref
              (recur (constrain-jobs jobs n parse-ch) (remove-job jobs :item port) study defs refs)

              :item-def
              (let [create-ref (map #(create-item-ref-job (defs (:study-id val)) %))
                    ref-vec [(:study-id val) :item-refs (:id (:data val))]
                    ref-jobs (eduction create-ref (get-in refs ref-vec))
                    jobs (-> (remove-job jobs :item port)
                             (add-jobs :item ref-jobs))]
                (recur (constrain-jobs jobs n parse-ch) jobs study defs (dissoc-in refs ref-vec))))))

        (if (= 0 (:count jobs))
          (log/info "Finished!")
          (recur [] jobs study defs refs))))))

