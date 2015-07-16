(ns lens.import
  (:use plumbing.core)
  (:require [clojure.core.async :as async :refer [go-loop]]
            [clojure.tools.logging :as log]
            [schema.core :as s]
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

(defn handle-study [state val]
  (let [job (->> (async/chan 1 (assoc-xf :type :study))
                 (async/pipe (api/upsert-study! (:parent state) val)))]
    (update state :jobs #(add-job % :study job))))

(defn handle-form-ref [state val]
  (update state :refs #(collect-form-ref % val)))

(defn handle-form-def [state val]
  (let [job (submit-job! api/upsert-form-def! :form-def (:parent state) val)]
    (update state :jobs #(add-job % :form job))))

(defn handle-item-group-ref [state val]
  (update state :refs #(collect-item-group-ref % val)))

(defn handle-item-group-def [state val]
  (let [job (submit-job! api/upsert-item-group-def! :item-group-def (:parent state) val)]
    (update state :jobs #(add-job % :item-group job))))

(defn- handle-item-ref [state val]
  (update state :refs #(collect-item-ref % val)))

(defn- handle-item-def [state val]
  (let [job (submit-job! api/upsert-item-def! :item-def (:parent state) val)]
    (update state :jobs #(add-job % :item job))))

(defn- handler [type]
  (case type
    :form-ref handle-form-ref
    :form-def handle-form-def
    :item-group-ref handle-item-group-ref
    :item-group-def handle-item-group-def
    :item-ref handle-item-ref
    :item-def handle-item-def))

(defn handle-study-job [state port val]
  (-> (update state :jobs #(remove-job % :study port))
      (assoc :parent val)))

(defn handle-form-def-job [state port val]
  (-> (update state :jobs #(remove-job % :form port))
      (update :defs #(collect-form-def % val))))

(defn handle-item-group-ref-job [state port _]
  (update state :jobs #(remove-job % :item-group port)))

(defn handle-item-group-def-job [state port val]
  (let [create-ref (map #(create-item-group-ref-job (get-in state [:defs (:study-id val)]) %))
        ref-vec [(:study-id val) :item-group-refs (:id (:data val))]
        ref-jobs (eduction create-ref (get-in state [:refs ref-vec]))]
    (-> (update state :jobs #(-> (remove-job % :item-group port)
                                 (add-jobs :item-group ref-jobs)))
        (update :defs #(collect-item-group-def % val))
        (update :refs #(dissoc-in % ref-vec)))))

(defn handle-item-ref-job [state port _]
  (update state :jobs #(remove-job % :item port)))

(defn handle-item-def-job [state port val]
  (let [create-ref (map #(create-item-ref-job (get-in state [:defs (:study-id val)]) %))
        ref-vec [(:study-id val) :item-refs (:id (:data val))]
        ref-jobs (eduction create-ref (get-in state [:refs ref-vec]))]
    (-> (update state :jobs #(-> (remove-job % :item port)
                                 (add-jobs :item ref-jobs)))
        (update :refs #(dissoc-in % ref-vec)))))

(defn- job-handler [type]
  (case type
    :study handle-study-job
    ;:form-ref handle-form-ref-job
    :form-def handle-form-def-job
    :item-group-ref handle-item-group-ref-job
    :item-group-def handle-item-group-def-job
    :item-ref handle-item-ref-job
    :item-def handle-item-def-job))

(defn import! [service-document n parse-ch]
  (let [constrain-jobs
        (fn [state]
          (if (< (:count (:jobs state)) n) [parse-ch] []))]
    (go-loop [ports [parse-ch]
              state {:parent service-document
                     :jobs {:count 0}
                     :defs {}
                     :refs {}}]
      (let [[val port] (async/alts! (into ports (select-jobs (:jobs state))))]
        (if-let [type (:type val)]
          (if (= parse-ch port)
            (if (= :study type)
              (recur [] (handle-study state val))
              (recur (constrain-jobs state) ((handler type) state val)))
            (recur (constrain-jobs state) ((job-handler type) state port val)))

          (if (= 0 (:count (:jobs state)))
            (log/info "Finished!")
            (recur [] state)))))))

