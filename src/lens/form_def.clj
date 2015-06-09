(ns lens.form-def
  (:use plumbing.core)
  (:require [lens.api :as api]
            [lens.event-bus :as bus]
            [lens.parent-resolver :refer [parent-resolver]]))

(defn- form-def-props [m]
  (select-keys m [:id :name :description]))

(defn- form-def-with-study-handler [bus]
  (fn [study form-def]
    (->> (form-def-props form-def)
         (api/upsert-form-def! study)
         (bus/publish-from! bus :form-def))))

(defn form-def-importer []
  (parent-resolver :study :form-def form-def-with-study-handler))
