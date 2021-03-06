(ns matthiasn.systems-toolbox.component
  #?(:cljs (:require-macros [cljs.core.async.macros :as cam :refer [go-loop]]))
  (:require
    #?(:clj  [clojure.core.match :refer [match]]
       :cljs [cljs.core.match :refer-macros [match]])
    #?(:clj  [clojure.core.async :refer [<! >! >!! chan put! sub pipe mult tap pub buffer sliding-buffer dropping-buffer go-loop timeout]]
       :cljs [cljs.core.async :refer [<! >! chan put! sub pipe mult tap pub buffer sliding-buffer dropping-buffer timeout]])
    #?(:clj  [clojure.tools.logging :as log])
    #?(:clj  [clojure.pprint :as pp]
       :cljs [cljs.pprint :as pp])
    #?(:cljs [cljs-uuid-utils.core :as uuid])))

#?(:clj  (defn now [] (System/currentTimeMillis))
   :cljs (defn now [] (.getTime (js/Date.))))

#?(:clj  (defn make-uuid [] (str (java.util.UUID/randomUUID)))
   :cljs (defn make-uuid [] (str (uuid/make-random-uuid))))

#?(:cljs (def request-animation-frame
           (or (.-requestAnimationFrame js/window)
               (.-webkitRequestAnimationFrame js/window)
               (.-mozRequestAnimationFrame js/window)
               (.-msRequestAnimationFrame js/window)
               (fn [callback] (js/setTimeout callback 17)))))

(defn make-chan-w-buf
  "Create a channel with a buffer of the specified size and type."
  [config]
  (match config
         [:sliding n] (chan (sliding-buffer n))
         [:buffer n] (chan (buffer n))
         :else (prn "invalid: " config)))

(def component-defaults
  {:in-chan               [:buffer 1]
   :sliding-in-chan       [:sliding 1]
   :throttle-ms           1
   :out-chan              [:buffer 1]
   :sliding-out-chan      [:sliding 1]
   :firehose-chan         [:buffer 1]
   :snapshots-on-firehose true
   :msgs-on-firehose      true
   :reload-cmp            true})

(defn add-to-msg-seq
  "Function for adding the current component ID to the sequence that the message has traversed
  thus far. The specified component IDs is either added when the cmp-seq is empty in the case
  of an initial send or when the message is received by a component. This avoids recording
  component IDs multiple times."
  [msg-meta cmp-id in-out]
  (let [cmp-seq (vec (:cmp-seq msg-meta))]
    (if (or (empty? cmp-seq) (= in-out :in))
      (assoc-in msg-meta [:cmp-seq] (conj cmp-seq cmp-id))
      msg-meta)))

(defn msg-handler-loop
  "Constructs a map with a channel for the provided channel keyword, with the buffer
  configured according to cfg for the channel keyword. Then starts loop for taking messages
  off the returned channel and calling the provided handler-fn with the msg.
  Does not process return values from the processing step; instead, put-fn needs to be
  called to produce output."
  [{:keys [handler-map all-msgs-handler cmp-state state-pub-handler put-fn cfg cmp-id firehose-chan snapshot-publish-fn
           unhandled-handler]
    :as cmp-map} chan-key]
  (let [chan (make-chan-w-buf (chan-key cfg))]
    (go-loop []
      (let [msg (<! chan)
            msg-meta (-> (merge (meta msg) {})
                         (add-to-msg-seq cmp-id :in)
                         (assoc-in [cmp-id :in-ts] (now)))
            [msg-type msg-payload] msg
            handler-map (merge {} handler-map)
            handler-fn (msg-type handler-map)
            msg-map (merge cmp-map {:msg         (with-meta msg msg-meta)
                                    :msg-type    msg-type
                                    :msg-meta    msg-meta
                                    :msg-payload msg-payload})]
        (try
          (when (= chan-key :sliding-in-chan)
            (state-pub-handler msg-map)
            (when (and (:snapshots-on-firehose cfg) (not= "firehose" (namespace msg-type)))
              (put! firehose-chan [:firehose/cmp-recv-state {:cmp-id cmp-id :msg msg}]))
            (<! (timeout (:throttle-ms cfg))))
          (when (= chan-key :in-chan)
            (when (and (:msgs-on-firehose cfg) (not= "firehose" (namespace msg-type)))
              (put! firehose-chan [:firehose/cmp-recv {:cmp-id cmp-id
                                                       :msg msg
                                                       :msg-meta msg-meta
                                                       :ts (now)}]))
            (when (= msg-type :cmd/get-state) (put-fn [:state/snapshot {:cmp-id cmp-id :snapshot @cmp-state}]))
            (when (= msg-type :cmd/publish-state) (snapshot-publish-fn))
            (when handler-fn (handler-fn msg-map))
            (when unhandled-handler
              (when-not (contains? handler-map msg-type) (unhandled-handler msg-map)))
            (when all-msgs-handler (all-msgs-handler msg-map)))
          #?(:clj  (catch Exception e (do (log/error e "Exception in" cmp-id "when receiving message:")
                                          (pp/pprint msg)))
             :cljs (catch js/Object e (do (.log js/console e (str "Exception in " cmp-id " when receiving message:"))
                                          (pp/pprint msg)))))
        (recur)))
    {chan-key chan}))

(defn make-put-fn
  "The put-fn is used inside each component for emitting messages to the outside world, from
   the component's point of view. All the component needs to know is the type of the message.
   Messages are vectors of two elements, where the first one is the type as a namespaced keyword
   and the second one is the message payload, like this: [:some/msg-type {:some \"data\"}]
   Message payloads are typically maps or vectors, but they can also be strings, primitive types
   nil. As long as they are local, they can even be any type, e.g. a channel, but once we want
   messages to traverse some message transport (WebSockets, some message queue), the types
   should be limited to what EDN or Transit can encode.
   Note that on component startup, this channel is not wired anywhere until the 'system-ready-fn'
   (below) is called, which pipes this channel into the actual out-chan. Thus, components should
   not try call more messages than fit in the buffer before the entire system is up."
  [{:keys [cmp-id put-chan cfg firehose-chan]}]
  (fn [msg]
    (let [msg-meta (-> (merge (meta msg) {})
                       (add-to-msg-seq cmp-id :out)
                       (assoc-in [cmp-id :out-ts] (now)))
          corr-id (make-uuid)
          tag (or (:tag msg-meta) (make-uuid))
          completed-meta (merge msg-meta {:corr-id corr-id :tag tag})
          msg-w-meta (with-meta msg completed-meta)
          msg-type (first msg)
          msg-from-firehose? (= "firehose" (namespace msg-type))]
      #?(:clj  (>!! put-chan msg-w-meta)
         :cljs (put! put-chan msg-w-meta))

      ;; Not all components should emit firehose messages. For example, messages that process
      ;; firehose messages should not do so again in order to avoid infinite messaging loops.
      ;; This behavior can be configured when the component is fired up.
      (when (:msgs-on-firehose cfg)
        ;; Some components may emit firehose messages directly. One such example is the
        ;; WebSockets component which can be used for relaying firehose messages, either
        ;; from client to server or from server to client. In those cases, the emitted
        ;; message should go on the firehose channel on the receiving end as such, not
        ;; wrapped as other messages would (see the second case in the if-clause).
        (if msg-from-firehose?
          (put! firehose-chan msg-w-meta)
          (put! firehose-chan [:firehose/cmp-put {:cmp-id cmp-id
                                                  :msg msg-w-meta
                                                  :msg-meta completed-meta
                                                  :ts (now)}]))))))

(defn make-snapshot-publish-fn
  "Creates a function for publishing changes to the component state atom as snapshot messages,"
  [{:keys [watch-state snapshot-xform-fn cmp-id sliding-out-chan cfg firehose-chan]}]
  (fn []
    (let [snapshot @watch-state
          snapshot-xform (if snapshot-xform-fn (snapshot-xform-fn snapshot) snapshot)
          snapshot-msg (with-meta [:app-state snapshot-xform] {:from cmp-id})
          state-firehose-chan (chan (sliding-buffer 1))]
      (pipe state-firehose-chan firehose-chan)
      (put! sliding-out-chan snapshot-msg)
      (when (:snapshots-on-firehose cfg)
        (put! state-firehose-chan
              [:firehose/cmp-publish-state {:cmp-id cmp-id
                                            :snapshot snapshot-xform
                                            :ts (now)}])))))

(defn detect-changes
  "Detect changes to the component state atom and then publish a snapshot using the
  'snapshot-publish-fn'."
  [{:keys [watch-state cmp-id snapshot-publish-fn]}]
  #?(:clj  (try
             (add-watch watch-state :watcher (fn [_ _ _ new-state] (snapshot-publish-fn)))
             (catch Exception e (do (log/error e "Exception in" cmp-id "when attempting to watch atom:")
                                    (pp/pprint watch-state))))
     :cljs (let [changed (atom true)]
             (letfn [(step []
                           (request-animation-frame step)
                           (when @changed
                             (snapshot-publish-fn)
                             (swap! changed not)))]
               (request-animation-frame step)
               (try (add-watch watch-state :watcher (fn [_ _ _ new-state] (reset! changed true)))
                    (catch js/Object e
                      (do (.log js/console e (str "Exception in " cmp-id " when attempting to watch atom:"))
                          (pp/pprint watch-state))))))))

(defn make-system-ready-fn
  "This function is called by the switchboard that wired this component when all other
  components are up and the channels between them connected. At this point, messages that
  were accumulated on the 'put-chan' buffer since startup are released. Also, the
  component state is published."
  [{:keys [put-chan out-chan snapshot-publish-fn]}]
   (fn []
     (pipe put-chan out-chan)
     (snapshot-publish-fn)))

(defn make-component
  "Creates a component with attached in-chan, out-chan, sliding-in-chan and sliding-out-chan.
  It takes the initial state atom, the handler function for messages on in-chan, and the
  sliding-handler function, which handles messages on sliding-in-chan.
  By default, in-chan and out-chan have standard buffers of size one, whereas sliding-in-chan
  and sliding-out-chan have sliding buffers of size one. The buffer sizes can be configured.
  The sliding-channels are meant for events where only ever the latest version is of interest,
  such as mouse moves or published state snapshots in the case of UI components rendering
  state snapshots from other components.
  Components send messages by using the put-fn, which is provided to the component when
  creating it's initial state, and then subsequently in every call to any of the handler
  functions. On every message send, a unique correlation ID is attached to every message.
  Also, messages are automatically assigned a tag, which is a unique ID that doesn't change
  when a message flows through the system. This tag can also be assigned manually by
  initially sending a message with the tag set on the metadata, as this tag will not be
  touched by the library whenever it exists already.
  The configuration of a component comes from merging the component defaults with the opts
  map that is passed on component creation the :opts key. The order of the merge operation
  allows overwriting the default settings."
  [{:keys [state-fn opts] :as cmp-conf}]
  (let [cfg (merge component-defaults opts)
        out-pub-chan (make-chan-w-buf (:out-chan cfg))
        cmp-map-1 (merge cmp-conf
                         {:put-chan         (make-chan-w-buf (:out-chan cfg)) ; used in put-fn, not connected at first
                          :out-chan         (make-chan-w-buf (:out-chan cfg)) ; outgoing chan, used in mult and pub
                          :cfg              cfg
                          :firehose-chan    (make-chan-w-buf (:firehose-chan cfg)) ; channel for publishing all messages
                          :sliding-out-chan (make-chan-w-buf (:sliding-out-chan cfg))}) ; chan for publishing snapshots
        put-fn (make-put-fn cmp-map-1)
        state-map (if state-fn (state-fn put-fn) {:state (atom {})}) ; create state, either from state-fn or empty map
        state (:state state-map)
        watch-state (if-let [watch (:watch cfg)] (watch state) state) ; watchable atom
        cmp-map-2 (merge cmp-map-1 {:watch-state watch-state})
        cmp-map-3 (merge cmp-map-2 {:snapshot-publish-fn (make-snapshot-publish-fn cmp-map-2)})
        cmp-map (merge cmp-map-3 {:out-mult          (mult (:out-chan cmp-map-3))
                                  :firehose-mult     (mult (:firehose-chan cmp-map-3))
                                  :out-pub           (pub out-pub-chan first)
                                  :state-pub         (pub (:sliding-out-chan cmp-map-3) first)
                                  :cmp-state         state
                                  :put-fn            put-fn
                                  :system-ready-fn   (make-system-ready-fn cmp-map-3)
                                  :shutdown-fn       (:shutdown-fn state-map)
                                  :state-snapshot-fn (fn [] @watch-state)})]
    (tap (:out-mult cmp-map) out-pub-chan)  ; connect out-pub-chan to out-mult
    (detect-changes cmp-map) ; publish snapshots when changes are detected
    (merge cmp-map
           (msg-handler-loop cmp-map :in-chan)
           (msg-handler-loop cmp-map :sliding-in-chan))))

(defn send-msg
  "Send message to the specified component."
  [component msg]
  (put! (:in-chan component) msg))
