(ns lens.study-event-def
  (:use plumbing.core)
  (:require [clojure.tools.logging :as log]
            [lens.api :as api]
            [lens.event-bus :as bus]
            [lens.parent-resolver :refer [parent-resolver]]))

(defn- study-event-def-props [m]
  (select-keys m [:id :name]))

(defn- odm-study-event-def-with-study-handler [bus]
  (fn [study {:keys [id] :as odm-study-event-def}]
    (if-let [study-event-def (->> (study-event-def-props odm-study-event-def)
                                  (api/upsert-study-event-def! study))]
      (bus/publish! bus :study-event-def study-event-def)
      (log/error "Error while upserting study-event-def" id))))

(defn study-event-def-importer []
  (parent-resolver :study :study-event-def odm-study-event-def-with-study-handler))
