(ns lens.item-group-def
  (:use plumbing.core)
  (:require [clojure.tools.logging :as log]
            [lens.api :as api]
            [lens.event-bus :as bus]
            [lens.parent-resolver :refer [parent-resolver]]))

(defn odm-item-group-def-with-study-handler [bus]
  (fn [study {:keys [id] :as odm-item-group-def}]
    (if-let [item-group-def (api/upsert-item-group-def! study
                                                        odm-item-group-def)]
      (bus/publish! bus :item-group-def item-group-def)
      (log/error "Error while upserting item-group-def" id))))

(defn item-group-def-importer []
  (parent-resolver :study :odm-item-group-def
                   odm-item-group-def-with-study-handler))
