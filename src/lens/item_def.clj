(ns lens.item-def
  (:use plumbing.core)
  (:require [clojure.tools.logging :as log]
            [lens.api :as api]
            [lens.event-bus :as bus]
            [lens.parent-resolver :refer [parent-resolver]]))

(defn- item-def-props [m]
  (select-keys m [:id :name :data-type :description :question]))

(defn- odm-item-def-with-study-handler [bus]
  (fn [study {:keys [id] :as odm-item-def}]
    (if-let [item-def (->> (item-def-props odm-item-def)
                           (api/upsert-item-def! study))]
      (bus/publish! bus :item-def item-def)
      (log/error "Error while upserting item-def" id))))

(defn item-def-importer []
  (parent-resolver :study :item-def odm-item-def-with-study-handler))
