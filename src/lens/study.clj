(ns lens.study
  (:use plumbing.core)
  (:require [com.stuartsierra.component :as component]
            [lens.api :as api]
            [lens.event-bus :as bus]))

(defn odm-study-handler [bus]
  (fnk [id :as odm-study]
    (when-let [study (api/create-or-update-study! odm-study)]
      (bus/publish! bus :study study))))

(defrecord StudyImporter [bus]
  component/Lifecycle
  (start [this]
    (->> (bus/listen-on bus :odm-study (odm-study-handler bus))
         (assoc this :stop-fn)))
  (stop [this]
    ((:stop-fn this))
    (dissoc this :stop-fn)))

(defn study-importer []
  (map->StudyImporter {}))
