(ns lens.study
  (:use plumbing.core)
  (:require [clojure.core.async :refer [<!!]]
            [clojure.tools.logging :as log]
            [com.stuartsierra.component :as component]
            [lens.api :as api]
            [lens.event-bus :as bus]))

(defn- study-handler
  "Returns a function which takes parsed study data, upserts the corresponding
  study and publishes it on the warehouse bus.

  Returns immediately."
  [service-document warehouse-bus]
  (fn [study-data]
    (->> (api/upsert-study! service-document study-data)
         (bus/publish-from! warehouse-bus :study))))

(defrecord StudyImporter [service-document parse-bus warehouse-bus]
  component/Lifecycle
  (start [this]
    (->> (study-handler service-document warehouse-bus)
         (bus/listen-on parse-bus :study)
         (assoc this :stop-fn)))
  (stop [this]
    ((:stop-fn this))
    (dissoc this :stop-fn)))

(defn study-importer [service-document]
  {:pre [service-document]}
  (map->StudyImporter {:service-document service-document}))
