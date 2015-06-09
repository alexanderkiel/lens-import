(ns user
  (:use plumbing.core)
  (:require [clojure.core.async :refer [<!!]]
            [clojure.java.io :as io]
            [clojure.pprint :refer [pprint]]
            [clojure.tools.namespace.repl :refer [refresh]]
            [com.stuartsierra.component :as component]
            [lens.study :refer [study-importer]]
            [lens.study-event-def :refer [study-event-def-importer]]
            [lens.form-def :refer [form-def-importer]]
            [lens.item-group-def :refer [item-group-def-importer]]
            [lens.item-def :refer [item-def-importer]]
            [lens.event-bus :as bus :refer [publish!!]]
            [lens.parse :refer [parse!]]
            [lens.api :as api])
  (:import [java.net URI]))

(defn create-system [^URI base-uri]
  (-> (component/system-map
        :parse-bus (bus/bus "parse")
        :warehouse-bus (bus/bus "warehouse")
        :study-importer (study-importer (api/extract-body-if-ok (<!! (api/fetch base-uri))))
        :study-event-def-importer (study-event-def-importer)
        :form-def-importer (form-def-importer)
        :item-group-def-importer (item-group-def-importer)
        :item-def-importer (item-def-importer))
      (component/system-using
        {:study-importer [:parse-bus :warehouse-bus]
         :form-def-importer [:parse-bus :warehouse-bus]
         :study-event-def-importer [:parse-bus :warehouse-bus]
         :item-group-def-importer [:parse-bus :warehouse-bus]
         :item-def-importer [:parse-bus :warehouse-bus]})))

(def system nil)

(defn init []
  (alter-var-root #'system (constantly (create-system (URI/create "http://localhost:5001")))))

(defn start []
  (alter-var-root #'system component/start))

(defn stop []
  (alter-var-root #'system component/stop))

(defn startup []
  (init)
  (start))

(defn reset []
  (stop)
  (refresh :after 'user/startup))

(comment

  (startup)
  (reset)
  (pst)

  (->> #_"samples/9814_who_five_well-being_.ODM.xml"
       #_"samples/9840_nci_standard_adverse.ODM.xml"
       "samples/nci2.xml"
       (io/input-stream)
       (parse! (:parse-bus system))
       (time))
  )
