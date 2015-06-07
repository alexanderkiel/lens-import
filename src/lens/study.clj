(ns lens.study
  (:use plumbing.core)
  (:require [clojure.tools.logging :as log]
            [com.stuartsierra.component :as component]
            [lens.api :as api]
            [lens.event-bus :as bus]))

(defn odm-study-handler [find-study-uri bus]
  (fnk [id :as odm-study]
    (if-let [study (api/upsert-study! find-study-uri odm-study)]
      (bus/publish! bus :study study)
      (log/error "Error while upserting study" id))))

(defrecord StudyImporter [find-study-uri bus]
  component/Lifecycle
  (start [this]
    (->> (bus/listen-on bus :odm-study (odm-study-handler find-study-uri bus))
         (assoc this :stop-fn)))
  (stop [this]
    ((:stop-fn this))
    (dissoc this :stop-fn)))

(defn study-importer [find-study-uri]
  (map->StudyImporter {:find-study-uri find-study-uri}))
