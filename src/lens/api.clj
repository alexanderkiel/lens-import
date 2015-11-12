(ns lens.api
  (:use plumbing.core)
  (:require [clojure.core.async :refer [go <!]]
            [clojure.tools.logging :as log]
            [hap-client.core :as hap]
            [async-error.core :refer [<? go-try]]
            [schema.core :as s]))

;; ---- Schema ----------------------------------------------------------------

(def SD
  {:data {:name s/Str :version s/Str s/Any s/Any}
   s/Any s/Any})

;; ---- Helper ----------------------------------------------------------------

(defn- query [doc id]
  (or (-> doc :queries id)
      (log/error "Can't find query" id "in" (keys (:queries doc)))))

(defn- form [doc id]
  (or (-> doc :forms id)
      (log/error "Can't find form" id "in" (keys (:forms doc)))))

(defn update-rep!
  "Returns a channel conveying the updated representation or any errors."
  [rep changes]
  (let [edited (merge rep {:data (dissoc changes :type)})]
    (if (not= rep edited)
      (hap/update (:self (:links rep)) edited)
      (go rep))))

(defn upsert! [find-query create-form {:keys [id] :as data}]
  {:pre [find-query id]}
  (go-try
    (let [result (<! (hap/query find-query {:id id}))]
      (if (instance? Throwable result)
        (if (= 404 (:status (ex-data result)))
          (<? (hap/fetch (<? (hap/create create-form data))))
          (throw result))
        (<? (update-rep! result data))))))

(defn create-ref! [find-query create-form data]
  (go-try
    (let [result (<! (hap/query find-query data))]
      (if (instance? Throwable result)
        (if (= 404 (:status (ex-data result)))
          (<? (hap/fetch (<? (hap/create create-form data))))
          (throw result))
        result))))

;; ---- Study -----------------------------------------------------------------

(s/defn upsert-study! [service-document :- SD study-data]
  (upsert! (query service-document :lens/find-study)
           (form service-document :lens/create-study)
           study-data))

;; ---- Study Event Def -------------------------------------------------------

(defn upsert-study-event-def! [study study-event-data]
  {:pre [study (:id study-event-data)]}
  (upsert! (query study :lens/find-study-event-def)
           (form study :lens/create-study-event-def)
           study-event-data))

;; ---- Form Ref --------------------------------------------------------------

(defn create-form-ref! [study-event-def {:keys [form-id]}]
  {:pre [study-event-def form-id]}
  (create-ref! (query study-event-def :lens/find-form-ref)
               (form study-event-def :lens/create-form-ref)
               {:form-id form-id}))

;; ---- Form Def --------------------------------------------------------------

(defn upsert-form-def! [study form-data]
  {:pre [study (:id form-data)]}
  (upsert! (query study :lens/find-form-def)
           (form study :lens/create-form-def)
           (dissoc form-data :study-id)))

;; ---- Item Group Ref --------------------------------------------------------

(defn create-item-group-ref! [form-def {:keys [item-group-id]}]
  {:pre [form-def item-group-id]}
  (create-ref! (query form-def :lens/find-item-group-ref)
               (form form-def :lens/create-item-group-ref)
               {:item-group-id item-group-id}))

;; ---- Item Group Def --------------------------------------------------------

(defn upsert-item-group-def! [study item-group-data]
  {:pre [study (:id item-group-data)]}
  (upsert! (query study :lens/find-item-group-def)
           (form study :lens/create-item-group-def)
           (dissoc item-group-data :study-id)))

;; ---- Item Ref --------------------------------------------------------

(defn create-item-ref! [item-group-def {:keys [item-id]}]
  {:pre [item-group-def item-id]}
  (create-ref! (query item-group-def :lens/find-item-ref)
               (form item-group-def :lens/create-item-ref)
               {:item-id item-id}))

;; ---- Item Def --------------------------------------------------------

(defn upsert-item-def! [study item-data]
  {:pre [study (:id item-data)]}
  (upsert! (query study :lens/find-item-def)
           (form study :lens/create-item-def)
           (dissoc item-data :study-id)))
