(ns lens.study
  (:use plumbing.core)
  (:require [clojure.tools.logging :as log]
            [com.stuartsierra.component :as component]
            [lens.api :as api]
            [lens.event-bus :as bus]))

(defn- study-props [m]
  (select-keys m [:id :name :description]))

(defn- odm-study-handler [service-document warehouse-bus]
  (fnk [id :as odm-study]
    (if-let [study (api/upsert-study! service-document (study-props odm-study))]
      (bus/publish! warehouse-bus :study study)
      (log/error "Error while upserting study" id))))

(defrecord StudyImporter [service-document parse-bus warehouse-bus]
  component/Lifecycle
  (start [this]
    (->> (odm-study-handler service-document warehouse-bus)
         (bus/listen-on parse-bus :study)
         (assoc this :stop-fn)))
  (stop [this]
    ((:stop-fn this))
    (dissoc this :stop-fn)))

(defn study-importer [service-document]
  {:pre [service-document]}
  (map->StudyImporter {:service-document service-document}))
