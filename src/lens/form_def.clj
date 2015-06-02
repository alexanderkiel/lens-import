(ns lens.form-def
  (:use plumbing.core)
  (:require [clojure.tools.logging :as log]
            [lens.api :as api]
            [lens.event-bus :as bus]
            [lens.parent-resolver :refer [parent-resolver]]))

(defn odm-form-def-with-study-handler [bus]
  (fn [study {:keys [id] :as odm-form-def}]
    (if-let [form-def (api/upsert-form-def! study odm-form-def)]
      (bus/publish! bus :form-def form-def)
      (log/error "Error while upserting form-def" id))))

(defn form-def-importer []
  (parent-resolver :study :odm-form-def odm-form-def-with-study-handler))
