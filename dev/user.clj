(ns user
  (:use plumbing.core)
  (:require [clojure.core.async :refer [<!!]]
            [clojure.java.io :as io]
            [clojure.pprint :refer [pprint]]
            [clojure.tools.namespace.repl :refer [refresh]]
            [hap-client.core :as hap]
            [lens.parse :refer [parse!]]
            [lens.import :refer [import!]]
            [lens.util :refer [<??]])
  (:import [java.net URI]))

(comment

  (time
    (let [service-document (<?? (hap/fetch (URI/create "http://localhost:5003/wh")))
          ch (parse! (io/input-stream "/home/akiel/coding/odm-export/all.xml"))
          res (<!! (import! service-document 100 ch))]
      (if (instance? Throwable res)
        (println res)
        (println "Finished!"))))

  )
