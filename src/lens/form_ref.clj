(ns lens.form-ref
  (:use plumbing.core)
  (:require [lens.api :as api]
            [lens.event-bus :as bus]
            [lens.ref-resolver :refer [ref-resolver]]))

(defn- form-ref-handler [bus]
  (fn [study-event-def form-ref _]
    (->> (api/create-form-ref! study-event-def form-ref)
         (bus/publish-from! bus :form-ref))))

(defn form-ref-importer []
  (ref-resolver "study-event" "form" :form-id form-ref-handler))
