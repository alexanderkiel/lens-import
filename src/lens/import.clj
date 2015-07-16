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

(defn- handle-item-ref [state val]
  (update state :refs #(collect-item-ref % val)))

(defn- handle-item-def [state val]
  (let [job (submit-job! api/upsert-item-def! :item-def (:parent state) val)]
    (update state :jobs #(add-job % :item job))))

(defn import! [service-document n parse-ch]
  (let [constrain-jobs
        (fn [state]
          (if (< (:count (:jobs state)) n) [parse-ch] []))]
    (go-loop [ports [parse-ch]
              state {:parent service-document
                     :jobs {:count 0}
                     :defs {}
                     :refs {}}]
      (log/debug "--------------------------------------------------")
      (log/debug "Parent:" (:data (:parent state)))
      (log/debug "Jobs:  " (map-vals count (dissoc (:jobs state) :count)))
      (log/debug "--------------------------------------------------")
      (let [[val port] (async/alts! (into ports (select-jobs (:jobs state))))]
        (if val
          (if (= parse-ch port)
            (do (log/debug "Parsed" (:type val) (:id val))
                (case (:type val)
                  :study
                  (let [job (async/pipe (api/upsert-study! (:parent state) val)
                                        (async/chan 1 (assoc-xf :type :study)))]
                    (recur [] (update state :jobs #(add-job % :study job))))

                  :form-ref
                  (recur (constrain-jobs state)
                         (update state :refs #(collect-form-ref % val)))

                  :form-def
                  (let [job (submit-job! api/upsert-form-def! :form-def (:parent state) val)]
                    (recur (constrain-jobs state)
                           (update state :jobs #(add-job % :form job))))

                  :item-group-ref
                  (recur (constrain-jobs state)
                         (update state :refs #(collect-item-group-ref % val)))

                  :item-group-def
                  (let [job (submit-job! api/upsert-item-group-def! :item-group-def (:parent state) val)]
                    (recur (constrain-jobs state)
                           (update state :jobs #(add-job % :item-group job))))

                  :item-ref
                  (recur (constrain-jobs state) (handle-item-ref state val))

                  :item-def
                  (recur (constrain-jobs state) (handle-item-def state val))))

            ;; jobs
            (do
              (log/debug "Finished" (:type val) (:id (:data val)))
              (case (:type val)
                :study
                (recur (constrain-jobs state)
                       (-> (update state :jobs #(remove-job % :study port))
                           (assoc :parent val)))

                :form-def
                (recur (constrain-jobs state)
                       (-> (update state :jobs #(remove-job % :form port))
                           (update :defs #(collect-form-def % val))))

                :item-group-ref
                (recur (constrain-jobs state)
                       (update state :jobs #(remove-job % :item-group port)))

                :item-group-def
                (let [create-ref (map #(create-item-group-ref-job (get-in state [:defs (:study-id val)]) %))
                      ref-vec [(:study-id val) :item-group-refs (:id (:data val))]
                      ref-jobs (eduction create-ref (get-in state [:refs ref-vec]))]
                  (recur (constrain-jobs state)
                         (-> (update state :jobs #(-> (remove-job % :item-group port)
                                                      (add-jobs :item-group ref-jobs)))
                             (update :defs #(collect-item-group-def % val))
                             (update :refs #(dissoc-in % ref-vec)))))

                :item-ref
                (recur (constrain-jobs state)
                       (update state :jobs #(remove-job % :item port)))

                :item-def
                (let [create-ref (map #(create-item-ref-job (get-in state [:defs (:study-id val)]) %))
                      ref-vec [(:study-id val) :item-refs (:id (:data val))]
                      ref-jobs (eduction create-ref (get-in state [:refs ref-vec]))]
                  (recur (constrain-jobs state)
                         (-> (update state :jobs #(-> (remove-job % :item port)
                                                      (add-jobs :item ref-jobs)))
                             (update :refs #(dissoc-in % ref-vec))))))))

          (if (= 0 (:count (:jobs state)))
            (log/info "Finished!")
            (recur [] state)))))))

