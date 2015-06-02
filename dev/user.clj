(ns user
  (:use plumbing.core)
  (:require [clojure.java.io :as io]
            [clojure.pprint :refer [pprint]]
            [clojure.tools.namespace.repl :refer [refresh]]
            [com.stuartsierra.component :as component]
            [lens.study :refer [study-importer]]
            [lens.form-def :refer [form-def-importer]]
            [lens.item-group-def :refer [item-group-def-importer]]
            [lens.event-bus :as bus :refer [publish!]]
            [lens.parse :refer [parse!]]))

(def system
  (-> (component/system-map
        :bus (bus/bus)
        :study-importer (study-importer)
        :form-def-importer (form-def-importer)
        :item-group-def-importer (item-group-def-importer))
      (component/system-using
        {:study-importer [:bus]
         :form-def-importer [:bus]
         :item-group-def-importer [:bus]})))

(defn start []
  (alter-var-root #'system component/start))

(defn stop []
  (alter-var-root #'system component/stop))

(defn startup []
  (start))

(defn reset []
  (stop)
  (refresh :after 'user/startup))

(comment

  (startup)
  (reset)
  (pst)

  (->> "samples/9814_who_five_well-being_.ODM.xml"
       #_"samples/9840_nci_standard_adverse.ODM.xml"
       (io/input-stream)
       (parse! (:bus system)))

  )
