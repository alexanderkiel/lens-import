(ns lens.api
  (:use plumbing.core)
  (:require [clojure.core.async :refer [go]]
            [clojure.tools.logging :as log]
            [hap-client.core :as hap]
            [lens.util :refer [<?]]))

(defn- query [doc id]
  (or (-> doc :actions id)
      (log/error "Can't find query" id "in" (keys (:actions doc)))))

(defn- form [doc id]
  (or (-> doc :actions id)
      (log/error "Can't find form" id "in" (keys (:actions doc)))))

(defn update-rep!
  "Returns a channel conveying the updated representation or any errors."
  [rep changes]
  (let [edited (merge rep changes)]
    (when (not= rep edited)
      (let [res (:self (:links rep))]
        (hap/update res edited)))
    (go rep)))

(defn upsert! [find-query create-form {:keys [id] :as data}]
  {:pre [find-query id]}
  (go
    (try
      (if-let [result (<? (hap/execute find-query {:id id}))]
        (<? (update-rep! result data))
        (<? (hap/fetch (<? (hap/create create-form data)))))
      (catch Throwable t t))))

;; ---- Study -----------------------------------------------------------------

(defn upsert-study! [service-document study-data]
  {:pre [service-document]}
  (upsert! (query service-document :lens/find-study)
           (form service-document :lens/create-study)
           study-data))

;; ---- Study Event Def -------------------------------------------------------

(defn upsert-study-event-def! [study m]
  {:pre [study]}
  (upsert! (query study :lens/find-study-event-def)
           (form study :lens/create-study-event-def)
           m))

;; ---- Form Def --------------------------------------------------------------

(defn upsert-form-def! [study m]
  {:pre [study]}
  (upsert! (query study :lens/find-form-def)
           (form study :lens/create-form-def)
           m))

;; ---- Item Group Def --------------------------------------------------------

(defn upsert-item-group-def! [study m]
  {:pre [study]}
  (upsert! (query study :lens/find-item-group-def)
           (form study :lens/create-item-group-def)
           m))

;; ---- Item Def --------------------------------------------------------

(defn upsert-item-def! [study m]
  {:pre [study]}
  (upsert! (query study :lens/find-item-def)
           (form study :lens/create-item-def)
           m))
