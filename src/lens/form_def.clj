(ns lens.form-def
  (:use plumbing.core)
  (:require [clojure.tools.logging :as log]
            [lens.api :as api]
            [lens.event-bus :as bus]
            [lens.parent-resolver :refer [parent-resolver]]))

(defn- form-def-props [m]
  (select-keys m [:id :name :description]))

(defn- odm-form-def-with-study-handler [bus]
  (fn [study {:keys [id] :as odm-form-def}]
    (if-let [form-def (->> (form-def-props odm-form-def)
                           (api/upsert-form-def! study))]
      (bus/publish! bus :form-def form-def)
      (log/error "Error while upserting form-def" id))))

(defn form-def-importer []
  (parent-resolver :study :form-def odm-form-def-with-study-handler))
