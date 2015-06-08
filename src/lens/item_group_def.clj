(ns lens.item-group-def
  (:use plumbing.core)
  (:require [clojure.tools.logging :as log]
            [lens.api :as api]
            [lens.event-bus :as bus]
            [lens.parent-resolver :refer [parent-resolver]]))

(defn- item-group-def-props [m]
  (select-keys m [:id :name :description]))

(defn- odm-item-group-def-with-study-handler [bus]
  (fn [study {:keys [id] :as odm-item-group-def}]
    (if-let [item-group-def (->> (item-group-def-props odm-item-group-def)
                                 (api/upsert-item-group-def! study))]
      (bus/publish! bus :item-group-def item-group-def)
      (log/error "Error while upserting item-group-def" id))))

(defn item-group-def-importer []
  (parent-resolver :study :item-group-def
                   odm-item-group-def-with-study-handler))
