(ns web-stats.main
  (:require
   [clj-etl-utils.log :as log]
   [noir.server :as server]
   ;; NB: cheat and use teporingo's redis lib for now...
   [teporingo.redis :as tr]
   [perceptor.provider :as perceptor])
  (:use
   noir.core
   hiccup.page-helpers
   hiccup.form-helpers
   [noir.response :only [json]]
   [teporingo.redis :only [*jedis*]]
   [perceptor.provider :only [*new-events* *provider*]]
   [clj-etl-utils.lang-utils :only [raise]]))

(def *config* (atom {:port 8080}))

(defonce *server* (atom nil))

(defonce *esp* (atom nil))

(defn restart-server []
  (if @*server*
    (do
      (server/stop @*server*)
      (reset! *server* nil)))
  (reset! *server* (server/start (get @*config* :port))))

(defn layout [title & body]
  (html5
   [:head
    (include-js "/js/jquery-1.5.1.min.js")
    (include-js "/js/app.js")
    (include-css "/css/app.css")
    [:title title]]
   body))

(defpage "/" {}
  (layout
      "perCEPtor Example: Stats"
    [:pre#stat-info
     "..stats go here.."]
    [:form {:id "event" :method "POST" :action "/eventnew"}
     (text-field :event-type "GenericEvent")
     (text-field :stock "xyz")
     (text-field :price "1.99")
     [:br]
     [:button#post-event "Post Event"]]
    [:div#footer
     [:p
      [:a {:href "/"} "Home"]]]))

(defpage [:post "/event/new"] {:keys [event-type stock price]}
  (log/infof "post event: %s [%s %s]" event-type stock price)
  (binding [*provider* @*esp*]
    (perceptor/emit-event "StockEvent"
                          "stock" stock
                          "price" (Double/parseDouble price)))
  (json [{:result "ok"}]))


(defpage "/js/*" params
  (let [f (get params :*)
        f (java.io.File. f)]
    (when (.exists f)
      (slurp f))))

(defpage "/css/*" params
  (let [f (get params :*)
        f (java.io.File. f)]
    (when (.exists f)
      (slurp f))))

;; read all stats from REDIS
(defpage "/stats" {}
  (tr/with-jedis :local
    (json [{:result
            (reduce
             (fn [m k]
               (assoc m k (.get *jedis* k)))
             {} (.keys *jedis* "stats.*"))}])))


(defn service-main []
  (log/infof "Starting services")
  (log/load-log4j-file "dev-resources/log4j.properties")
  (tr/register-redis-pool :local {:host "localhost"
                                  :port 6379})
  ;; pre-populate with 1 example
  (tr/with-jedis :local
    #_(.incr *jedis* "stats.example")
    (tr/with-jedis :local
      (.set *jedis* "stats.xyz.avg.60s" "1.00"))
    (tr/with-jedis :local
      (.set *jedis* "stats.xyz.trades.60s" "1.00")))

  (if-not (nil? @*esp*)
    (binding [*provider* @*esp*]
      (perceptor/stop-all-statements)))

  (perceptor/register-provider
   :stocks
   (fn [mgr]
     (perceptor/declare-type "StockEvent"
                             "stock" "string"
                             "price" "float")))

  (reset! *esp* (perceptor/make-provider :stocks))

  (perceptor/register-listener
   :trade-count-listener
   "select count(*) as count, stock from StockEvent.win:time(60 seconds) group by stock"
   (fn []
     (log/infof "count event: %s" (vec (map bean *new-events*)))
     (tr/with-jedis :local
       (doseq [e *new-events*]
         (let [props (.getProperties e)
               key   (format "stats.%s.trades.60s" (get props "stock"))
               count (str (get props "count"))]
           (log/infof " set [count]: %s/%s=%s/%s"
                      key (class key)
                      count (class count))
           (.set *jedis* key count))))))

  (perceptor/register-listener
   :trade-avg-listener
   "select avg(price) as price, stock from StockEvent.win:time(60 seconds) group by stock"
   (fn []
     (log/infof "avg event: %s" (vec (map bean *new-events*)))
     (tr/with-jedis :local
       (doseq [e *new-events*]
         (let [props (.getProperties e)
               key   (format "stats.%s.avg.60s" (get props "stock"))
               price (str (get props "price"))]
           (log/infof " set [avg]: %s/%s=%s/%s"
                      key (class key)
                      price (class price))
           (.set *jedis* key price))))))

  (binding [*provider* @*esp*]
    (perceptor/start-listener :trade-count-listener)
    (perceptor/start-listener :trade-avg-listener))

  ;; register the query / listener
  (restart-server))

(defn main [args]
  (service-main))


(comment
  (binding [*provider* @*esp*]
    (perceptor/emit-event "StockEvent"
                          "stock" "xyz"
                          "price" (Double/parseDouble "1.99")))

  (tr/with-jedis :local
    (.del *jedis* (into-array String (vec (.keys *jedis* "*")))))

  (service-main)


  )