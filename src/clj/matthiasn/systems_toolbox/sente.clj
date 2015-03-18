(ns matthiasn.systems-toolbox.sente
  (:gen-class)
  (:require [clojure.core.match :refer [match]]
            [matthiasn.systems-toolbox.component :as comp]
            [taoensso.sente :as sente]))

(defn make-handler
  "Create handler function for messages from WebSocket connection. Calls put-fn with received
   messages."
  [put-fn]
  (fn [{:keys [event]}]
    (match event
           [:chsk/state {:first-open? true}] (put-fn [:first-open true])
           [:chsk/recv payload]              (put-fn payload)
           [:chsk/handshake _]               ()
           :else (print "Unmatched event: %s" event))))

(defn make-state
  "Return clean initial component state atom."
  [put-fn]
  (let [ws (sente/make-channel-socket! "/chsk" {:type :auto})]
    (sente/start-chsk-router! (:ch-recv ws) (make-handler put-fn))
    ws))

(defn in-handler
  "Handle incoming messages: process / add to application state."
  [ws _ [cmd-type payload]]
  (let [state (:state ws)]
    ((:send-fn ws) [cmd-type (assoc payload :uid (:uid @state))])))

(defn component
  []
  (comp/make-component make-state in-handler nil {:atom false}))
