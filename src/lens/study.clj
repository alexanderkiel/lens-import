(ns lens.study
  (:use plumbing.core)
  (:require [com.stuartsierra.component :as component]
            [lens.api :as api]
            [lens.event-bus :as bus]))

(defrecord StudyImporter [bus]
  component/Lifecycle
  (start [this]
    (->> (bus/listen-on bus :study
           (fnk [id :as study]
             (let [uri (api/create-or-update-study! study)]
               (println "got study" id "at" uri)
               (bus/publish! bus :study-uri {:id id :uri uri}))))
         (assoc this :stop-fn)))
  (stop [this]
    ((:stop-fn this))
    (dissoc this :stop-fn)))

(defn study-importer []
  (map->StudyImporter {}))
