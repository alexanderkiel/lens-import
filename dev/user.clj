(ns user
  (:use plumbing.core)
  (:require [clojure.core.async :refer [<!!]]
            [clojure.java.io :as io]
            [clojure.pprint :refer [pprint]]
            [clojure.tools.namespace.repl :refer [refresh]]
            [com.stuartsierra.component :as component]
            [hap-client.core :as hap]
            [lens.study :refer [study-importer]]
            [lens.study-event-def :refer [study-event-def-importer]]
            [lens.form-def :refer [form-def-importer]]
            [lens.form-ref :refer [form-ref-importer]]
            [lens.item-group-def :refer [item-group-def-importer]]
            [lens.item-group-ref :refer [item-group-ref-importer]]
            [lens.item-def :refer [item-def-importer]]
            [lens.item-ref :refer [item-ref-importer]]
            [lens.event-bus :as bus :refer [publish!!]]
            [lens.parse :refer [parse!]]
            [lens.util :refer [<??]])
  (:import [java.net URI]))

(defn create-system [^URI base-uri]
  (-> (component/system-map
        :parse-bus (bus/bus "parse")
        :warehouse-bus (bus/bus "warehouse")
        :study-importer (study-importer (<?? (hap/fetch base-uri)))
        :study-event-def-importer (study-event-def-importer)
        :form-def-importer (form-def-importer)
        :form-ref-importer (form-ref-importer)
        :item-group-def-importer (item-group-def-importer)
        :item-group-ref-importer (item-group-ref-importer)
        :item-def-importer (item-def-importer)
        :item-ref-importer (item-ref-importer))
      (component/system-using
        {:study-importer [:parse-bus :warehouse-bus]
         :study-event-def-importer [:parse-bus :warehouse-bus]
         :form-def-importer [:parse-bus :warehouse-bus]
         :form-ref-importer [:parse-bus :warehouse-bus]
         :item-group-def-importer [:parse-bus :warehouse-bus]
         :item-group-ref-importer [:parse-bus :warehouse-bus]
         :item-def-importer [:parse-bus :warehouse-bus]
         :item-ref-importer [:parse-bus :warehouse-bus]})))

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
       "samples/9840_nci_standard_adverse.ODM.xml"
       #_"samples/nci2.xml"
       (io/input-stream)
       (parse! (:parse-bus system))
       (time))
  )
