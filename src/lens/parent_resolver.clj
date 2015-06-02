(ns lens.parent-resolver
  "Component which resolves parents of ODM entities like studies of form-defs."
  (:use plumbing.core)
  (:require [clojure.tools.logging :as log]
            [com.stuartsierra.component :as component]
            [lens.api :as api]
            [lens.event-bus :as bus]))

(defrecord ParentResolver [bus res-topic topic callback]
  component/Lifecycle
  (start [this]
    (->> (bus/wait-for bus res-topic topic (callback bus))
         (assoc this :stop-fn)))
  (stop [this]
    ((:stop-fn this))
    (dissoc this :stop-fn)))

(defn parent-resolver [res-topic topic callback]
  (map->ParentResolver {:res-topic res-topic :topic topic :callback callback}))
