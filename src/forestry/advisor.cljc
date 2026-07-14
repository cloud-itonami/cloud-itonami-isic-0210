(ns forestry.advisor
  "ForestryAdvisor -- the *contained intelligence node* for the
  community-silviculture coordination actor.

  It normalizes stand inventory patches, drafts a field-operation
  scheduling proposal against a stand, drafts a forest-health-concern
  flag, and drafts a supply-order proposal. CRITICAL: it is a smart-but-
  untrusted advisor. It returns a *proposal* (with a rationale + the
  fields it cited), never a committed record and NEVER a real equipment
  dispatch, harvest execution, or purchase order. Every output is
  censored downstream by `forestry.governor` before anything touches the
  SSoT -- see README `What this actor does NOT do`.

  Like every sibling actor's advisor, this is a deterministic mock so
  the actor graph runs offline and the governor contract is exercised
  end-to-end. In production this calls a real LLM (kotoba-llm or
  equivalent) with the same proposal shape.

  Proposal shape (all kinds):
    {:summary    str            ; human-facing draft / finding
     :rationale  str            ; why -- informational only, NOT trusted
                                 ; by the governor for any ground-truth
                                 ; check (see `forestry.governor`)
     :cites      [kw|str ..]    ; fields the advisor used
     :effect     kw             ; how a commit would mutate the SSoT --
                                 ; ALWAYS one of the closed
                                 ; #{:stand/upsert :field-operation/
                                 ; schedule :health-concern/flag
                                 ; :supply-order/propose} propose-shaped
                                 ; effects, NEVER a direct-equipment-
                                 ; control or harvest-finalize effect
     :stake      kw|nil         ; :coordination/health-concern | nil
     :confidence 0..1}

  CRITICAL invariant this advisor upholds: every request it is asked to
  route MUST itself carry `:effect :propose` (the request-level
  contract every caller of this actor agrees to) -- `forestry.governor`
  HARD-holds any request that doesn't, so a mis-wired caller can never
  reach a commit path even if this advisor were compromised."
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [clojure.string :as str]
            [forestry.registry :as registry]
            [forestry.store :as store]
            [langchain.model :as model]))

(defn- log-stand-record
  "Directory upsert -- the advisor only normalizes/validates the patch;
  it does not invent the stand's site, species, age or verification
  status. High confidence, low stakes."
  [_db {:keys [patch]}]
  {:summary    (str "林分記録更新: " (pr-str (keys patch)))
   :rationale  "入力patchの正規化のみ。新規事実の生成なし。"
   :cites      (vec (keys patch))
   :effect     :stand/upsert
   :value      patch
   :stake      nil
   :confidence 0.95})

(defn- schedule-field-operation
  "Draft a planting/thinning/harvest scheduling proposal against a
  stand. The advisor reports what it can see (stand verified?/age) in
  its rationale, but `forestry.governor` NEVER trusts this report --
  it independently re-derives verified?/maturity from the stand's own
  stored fields before any commit is possible."
  [db {:keys [subject value]}]
  (let [stand-id (:stand-id value)
        st (store/stand db stand-id)
        verified? (and st (registry/stand-verified? st))
        immature? (and st (= :harvest (:operation-type value))
                        (registry/stand-immature-for-harvest? st))]
    {:summary    (str subject " 向け作業予定提案 (" (:operation-type value) ")"
                      (when st (str " stand=" stand-id)))
     :rationale  (if st
                   (str "stand-verified?=" verified? " age-years=" (:age-years st)
                        " immature-for-harvest?=" immature?
                        " finalize?=" (boolean (:finalize? value)))
                   (str stand-id " が見つかりません"))
     :cites      (if st [stand-id] [])
     :effect     :field-operation/schedule
     :value      value
     :stake      nil
     :confidence (if (and verified? (not immature?) (not (:finalize? value))) 0.9 0.3)}))

(defn- flag-forest-health-concern
  "Draft a pest/disease/wildfire-risk flag. ALWAYS `:stake
  :coordination/health-concern` -- a health/safety concern is NEVER a
  proposal the advisor may quietly downgrade to low-stakes, and it is
  never gated on the referenced stand being verified (a concern can be
  raised about ANY stand, verified or not -- see README `What this
  actor does NOT do` re: never blocking safety-relevant reporting on an
  administrative technicality). See `forestry.phase`: no phase ever
  adds this op to a phase's `:auto` set; `forestry.governor` also
  always escalates on `:coordination/health-concern`. Two independent
  layers agree, deliberately."
  [db {:keys [subject value]}]
  (let [stand-id (:stand-id value)
        st (and stand-id (store/stand db stand-id))]
    {:summary    (str subject " 向け森林健康懸念報告 (" (:severity value) ")"
                      (when st (str " stand=" stand-id)))
     :rationale  (str "severity=" (:severity value) " description=" (:description value))
     :cites      (if st [stand-id] [])
     :effect     :health-concern/flag
     :value      value
     :stake      :coordination/health-concern
     :confidence 0.9}))

(defn- order-supplies
  "Draft a seedling/equipment procurement proposal. The advisor passes
  through the caller's own claimed total -- it does NOT invent one, and
  `forestry.governor` NEVER trusts it: it independently recomputes the
  total from the order's own line items via `forestry.registry/order-
  total` before any commit is possible."
  [_db {:keys [subject value]}]
  (let [total (registry/order-total value)
        matches? (registry/order-total-matches-claim? value)]
    {:summary    (str subject " 向け資材発注提案 (" (count (:items value)) " 品目)")
     :rationale  (str "claimed-total=" (:claimed-total value)
                      " independent-recompute=" total
                      " matches?=" matches?)
     :cites      (if (seq (:items value)) [subject] [])
     :effect     :supply-order/propose
     :value      value
     :stake      nil
     :confidence (if matches? 0.9 0.3)}))

(defn infer
  "Route a request to the right proposal generator.
  request: {:op kw :effect :propose :subject id ...op-specific...}"
  [db {:keys [op] :as request}]
  (case op
    :log-stand-record            (log-stand-record db request)
    :schedule-field-operation    (schedule-field-operation db request)
    :flag-forest-health-concern  (flag-forest-health-concern db request)
    :order-supplies              (order-supplies db request)
    {:summary "未対応の操作" :rationale (str op) :cites []
     :effect :noop :stake nil :confidence 0.0}))

;; ----------------------------- Advisor protocol -----------------------------

(defprotocol Advisor
  (-advise [advisor store request] "store + request -> proposal map"))

(defn mock-advisor
  "The deterministic advisor (the `infer` logic above). Default everywhere."
  [] (reify Advisor (-advise [_ st req] (infer st req))))

(def ^:private system-prompt
  (str "あなたは地域林業コーディネーターの助言者です。"
       "与えられた事実のみに基づき、提案を1つだけEDNマップで返します。"
       "説明や前置きは一切書かず、EDNだけを出力します。\n"
       "キー: :summary(人向けドラフト) :rationale(根拠/必ず事実から) "
       ":cites(使った事実キーのベクタ) "
       ":effect(:stand/upsert|:field-operation/schedule|"
       ":health-concern/flag|:supply-order/propose) "
       ":stake(:coordination/health-concern か nil) :confidence(0..1)。\n"
       "重要: 未検証の林分に対する作業予定を提案してはいけません。"
       "伐採計画の確定(finalize)や伐採機材の直接操作を絶対に提案してはいけません"
       "(この actor は提案のみを行い、実行は一切行いません)。"
       "資材発注の合計金額を偽って報告してはいけません。"))

(defn- facts-for [st {:keys [op subject value]}]
  (case op
    :log-stand-record           {:stand (store/stand st subject)}
    :schedule-field-operation   {:stand (store/stand st (:stand-id value))}
    :flag-forest-health-concern {:stand (and (:stand-id value) (store/stand st (:stand-id value)))}
    :order-supplies             {:order-total (registry/order-total value)}
    {}))

(defn- parse-proposal
  "Parse the model's EDN proposal defensively. Any parse/shape failure
  yields a safe low-confidence noop so `forestry.governor`
  escalates/holds -- an LLM hiccup can never auto-schedule a field
  operation, auto-flag a concern, or auto-place a supply order."
  [content]
  (let [p (try (edn/read-string (str/trim (str content)))
               (catch #?(:clj Exception :cljs :default) _ nil))]
    (if (map? p)
      (-> p
          (update :cites #(vec (or % [])))
          (update :confidence #(if (number? %) (double %) 0.0))
          (update :effect #(or % :noop)))
      {:summary "LLM応答を解釈できませんでした" :rationale (str content)
       :cites [] :effect :noop :stake nil :confidence 0.0})))

(defn llm-advisor
  "An advisor backed by a `langchain.model/ChatModel` (real inference)."
  ([chat-model] (llm-advisor chat-model {}))
  ([chat-model gen-opts]
   (reify Advisor
     (-advise [_ st req]
       (let [msgs [{:role :system :content system-prompt}
                   {:role :user :content (str "操作: " (:op req)
                                              "\n対象: " (:subject req)
                                              "\n事実: " (pr-str (facts-for st req)))}]
             resp (model/-generate chat-model msgs gen-opts)]
         (parse-proposal (:content resp)))))))

(defn trace
  "Decision-grounded audit record -- persisted to the :audit channel."
  [request proposal]
  {:t          :forestry-advisor-proposal
   :op         (:op request)
   :subject    (:subject request)
   :summary    (:summary proposal)
   :rationale  (:rationale proposal)
   :cites      (:cites proposal)
   :confidence (:confidence proposal)})
