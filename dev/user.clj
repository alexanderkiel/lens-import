(ns user
  (:use plumbing.core)
  (:require [clojure.core.async :refer [<!!]]
            [async-error.core :refer [<??]]
            [clojure.java.io :as io]
            [clojure.pprint :refer [pprint]]
            [hap-client.core :as hap]
            [lens.parse :refer [parse!]]
            [lens.import :refer [import!]]
            [schema.core :as s]))

(s/set-fn-validation! true)

(comment

  (time
    (let [service-document (<?? (hap/fetch "http://localhost:5001"))
          ch (parse! (io/input-stream "out.xml"))
          res (<!! (import! service-document 100 ch))]
      (if (instance? Throwable res)
        (println res)
        (println "Finished!"))))

  )
