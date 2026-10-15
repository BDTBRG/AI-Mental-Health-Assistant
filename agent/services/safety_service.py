"""Deterministic pre- and post-generation safety gates for mental-health chat."""

import re
import os
from dataclasses import dataclass
from typing import Literal


SafetyLevel = Literal["low", "uncertain", "high"]


@dataclass(frozen=True)
class SafetyAssessment:
    level: SafetyLevel
    reason: str


class SafetyService:
    HIGH_INTENT_PATTERNS = (
        re.compile(r"(?:我|自己|本人).{0,8}(?:今晚|今天|现在|马上|准备|打算|计划|想要|要|想).{0,12}(?:自杀|轻生|结束生命|伤害自己|自残|割腕|割(?:伤|开)?(?:了)?(?:自己的)?(?:手腕|腕部|手臂)|划(?:伤|开|破)?(?:了)?(?:自己的)?(?:手腕|腕部|手臂)|跳楼|吞药)"),
        re.compile(r"(?:我|自己|本人).{0,12}(?:故意|有意|特意).{0,12}(?:割|划|伤害).{0,8}(?:自己|手腕|腕部|手臂)"),
        re.compile(r"(?:我|自己|本人).{0,8}(?:想|打算|准备|计划|要).{0,8}(?:把)?(?:自己的)?(?:手腕|腕部|手臂).{0,4}(?:割(?:伤|开)?|划(?:伤|开|破))"),
        re.compile(r"(?:已经|正在).{0,8}(?:割腕|吞药|跳楼|自残|伤害自己)"),
        re.compile(r"(?:我|自己|本人).{0,8}(?:想杀|准备杀|要杀|伤害).{0,12}(?:他|她|别人|同事|家人|自己)"),
    )
    OVERDOSE_PATTERNS = (
        re.compile(
            r"(?:我|自己|本人|他|她|朋友|家人)?(?:刚刚|刚才|已经|正在|此刻|现在)?"
            r".{0,8}(?:吞下(?:了)?|吞了|服下(?:了)?|服了|服用了|吃下(?:了)?|吃了)"
            r".{0,12}(?:很多|一把|整瓶|一整瓶|一瓶|大量|过量|超量|大剂量|"
            r"(?:\d{2,}|十几|几十|上百|十[一二三四五六七八九]?|[二三四五六七八九]十[一二三四五六七八九]?|"
            r"[一二三四五六七八九两]?百[零〇一二三四五六七八九十两]{0,3}?)(?:片|粒|颗|瓶|包))"
            r".{0,8}(?:安眠药|药片|药物|药|\d{1,3}(?:片|粒|颗|瓶|包)|十几(?:片|粒|颗)|几十(?:片|粒|颗)|上百(?:片|粒|颗))"
        ),
        re.compile(r"(?:我|自己|本人|他|她|朋友|家人).{0,8}(?:服药|吃药|吞药).{0,8}(?:过量|超量|大剂量|整瓶|一把|很多)"),
        re.compile(r"(?:我|自己|本人|他|她|朋友|家人).{0,8}(?:过量|超量).{0,8}(?:服药|吃药|吞药)"),
    )
    ACUTE_ACTION_PATTERNS = (
        re.compile(
            r"(?:(?:我|自己|本人|他|她|朋友|家人)?(?:刚刚|刚才|已经|正在|此刻|现在).{0,6})?"
            r"(?:割(?:了)?(?:自己的)?(?:手腕|腕部|手臂)|划伤了?(?:自己|手腕|腕部|手臂)|"
            r"割伤了?(?:自己|手腕|腕部|手臂)|划破了?(?:自己|手腕|腕部|手臂)|自残了|自伤了|跳楼了|吞药了)"
        ),
        re.compile(r"把(?:自己的)?(?:手腕|腕部|手臂).{0,4}(?:割伤|划伤|割开|划开|划破)(?:了|一道口子|一道|一刀)?"),
        re.compile(r"(?:自己的)?(?:手腕|腕部|手臂).{0,4}(?:割伤|划伤|割开|划开|划破)(?:了|一道口子|一道|一刀)"),
    )
    ACCIDENT_CONTEXT_TERMS = (
        "做饭", "切菜", "做菜", "削苹果", "削水果", "削梨", "切水果", "切肉", "剁菜",
        "工作时", "工作中", "干活时", "运动时", "不小心", "不慎", "意外", "事故",
        "被刀划", "被刀割", "被割", "被划",
    )
    NEGATED_INTENT_PATTERN = re.compile(
        r"(?:没有|没|不(?!小心|慎)|未|不会|从未|并非|不是).{0,16}"
        r"(?:(?:想|打算|准备|计划).{0,8}(?:把)?(?:自己的)?(?:手腕|腕部|手臂).{0,4}(?:割(?:伤|开)?|划(?:伤|开))|"
        r"准备|打算|计划|想要|要|自杀|轻生|结束生命|伤害自己|自伤|自残|"
        r"割腕|割(?:了)?(?:自己的)?(?:手腕|腕部|手臂)|划伤了?(?:自己|手腕|腕部|手臂)|"
        r"割伤了?(?:自己|手腕|腕部|手臂)|跳楼|吞药|吞下药|吞了.{0,8}(?:药|安眠药)|"
        r"服了?.{0,8}(?:药|安眠药)|服用了?.{0,8}(?:药|安眠药)|吃了.{0,8}(?:药|安眠药))"
    )
    QUOTE_PAIRS = (("“", "”"), ("‘", "’"), ("「", "」"), ("『", "』"), ('"', '"'), ("'", "'"))
    UNCERTAIN_TERMS = (
        "自杀", "轻生", "结束生命", "不想活", "自伤", "自残", "割腕", "跳楼", "吞药", "伤害自己",
        "杀死", "杀人", "伤害别人", "想消失", "只想消失", "不想醒来", "不想再醒来",
        "永远不要醒来", "希望别再醒来", "活着没意思", "活着没有意义", "撑不下去", "撑不住了",
        "一了百了", "不想继续活", "割伤手腕", "划伤手腕", "割开手腕", "划开手腕",
        "划破手腕", "把手腕划开", "把手腕划破",
    )
    UNSAFE_RESPONSE_PATTERNS = (
        re.compile(r"(?:如何|怎样|步骤|方法|剂量).{0,24}(?:自杀|轻生|自残|割腕|吞药|跳楼)"),
        re.compile(r"(?:建议|推荐|可以).{0,16}(?:停药|加量|减量|换药)"),
        re.compile(r"(?:你患有|诊断为|你就是).{0,12}(?:抑郁症|焦虑症|双相障碍|精神分裂症)"),
    )
    AFFIRMATIVE_CLARIFICATION_ANSWERS = frozenset({
        "有", "有的", "是", "是的", "对", "对的", "嗯", "嗯嗯", "已经",
        "已经采取行动", "已经采取了行动", "已经做了", "采取了行动", "做了",
    })
    NEGATIVE_CLARIFICATION_ANSWERS = frozenset({
        "没有", "没有的", "没有打算", "没有计划", "没有行动", "没有采取行动",
        "没有做", "还没有", "目前没有", "现在没有", "暂时没有", "没打算",
        "没计划", "没行动", "没采取行动", "没做", "不是", "否",
    })
    EXPLICIT_CLARIFICATION_NEGATION = re.compile(
        r"^(?:(?:我)?(?:并没有|目前没有|现在没有|暂时没有|没有|没|未|不会))"
        r".{0,32}(?:打算|计划|行动|采取|伤害自己|伤害别人|自杀|轻生|自残|自伤|引用|回忆)"
    )
    AFFIRMATIVE_PREFIX = re.compile(
        r"^(?:有|是的?|对的?|嗯嗯?|确实|已经|我有)"
        r"(?:[，,。.!！?？、；;]|$)"
    )
    AFFIRMATIVE_RISK_DETAIL = re.compile(
        r"^(?:有|是的?|对的?|嗯嗯?|确实|已经|我有|我已经).{0,16}"
        r"(?:打算|计划|行动|采取|伤害自己|伤害别人|自杀|轻生|自残|自伤|想法|念头|做了)"
    )

    def assess(self, text: str) -> SafetyAssessment:
        normalized = re.sub(r"\s+", "", text or "").lower()
        if not normalized:
            return SafetyAssessment("low", "empty_input")

        uncertain_action_reason = None
        for pattern in self.OVERDOSE_PATTERNS:
            match = pattern.search(normalized)
            if match:
                if self._overlaps_quote(normalized, match.start(), match.end()):
                    uncertain_action_reason = "quoted_or_ambiguous_harm_reference"
                    continue
                if self._negated_in_context(normalized, match.start(), match.end()):
                    uncertain_action_reason = "negated_harm_action_reference"
                    continue
                # A suspected overdose needs urgent support even when the user says it
                # was accidental; intent and medical urgency are separate questions.
                return SafetyAssessment("high", "possible_overdose")

        for pattern in self.ACUTE_ACTION_PATTERNS:
            match = pattern.search(normalized)
            if match:
                if self._overlaps_quote(normalized, match.start(), match.end()):
                    uncertain_action_reason = "quoted_or_ambiguous_harm_reference"
                    continue
                if self._negated_in_context(normalized, match.start(), match.end()):
                    uncertain_action_reason = "negated_harm_action_reference"
                    continue
                if self._has_accident_context(normalized, match.start(), match.end()):
                    uncertain_action_reason = "possible_accidental_injury"
                    continue
                return SafetyAssessment("high", "possible_recent_harm_action")

        for pattern in self.HIGH_INTENT_PATTERNS:
            match = pattern.search(normalized)
            if match:
                if (self._overlaps_quote(normalized, match.start(), match.end())
                        or self._negated_in_context(normalized, match.start(), match.end())):
                    return SafetyAssessment("uncertain", "quoted_or_negated_harm_reference")
                return SafetyAssessment("high", "direct_harm_intent")

        if uncertain_action_reason:
            return SafetyAssessment("uncertain", uncertain_action_reason)

        if any(term in normalized for term in self.UNCERTAIN_TERMS):
            return SafetyAssessment("uncertain", "harm_reference_requires_clarification")

        return SafetyAssessment("low", "no_rule_match")

    def assess_clarification(
        self, text: str, prompt_kind: str = "risk_intent"
    ) -> SafetyAssessment:
        """Interpret an answer only while the conversation is awaiting safety clarification."""
        normalized = re.sub(r"\s+", "", (text or "").lower())
        compact = re.sub(r"[，,。.!！?？、；;]", "", normalized)
        if not compact:
            return SafetyAssessment("uncertain", "ambiguous_clarification_answer")

        assessment = self.assess(text)
        if assessment.level == "high":
            return assessment

        if prompt_kind == "safe_location":
            if compact in self.AFFIRMATIVE_CLARIFICATION_ANSWERS or self.AFFIRMATIVE_PREFIX.search(normalized):
                return SafetyAssessment("uncertain", "safe_location_confirmed_risk_still_unresolved")
            if (compact in self.NEGATIVE_CLARIFICATION_ANSWERS
                    or re.match(r"^(?:不安全|我不安全|没有安全的地方|没有人陪|没人陪)", compact)):
                return SafetyAssessment("high", "user_reports_unsafe_location")
            return SafetyAssessment("uncertain", "ambiguous_safe_location_answer")

        if (compact in self.AFFIRMATIVE_CLARIFICATION_ANSWERS
                or self.AFFIRMATIVE_PREFIX.search(normalized)
                or self.AFFIRMATIVE_RISK_DETAIL.search(normalized)):
            return SafetyAssessment("high", "affirmative_risk_confirmation")

        if (compact in self.NEGATIVE_CLARIFICATION_ANSWERS
                or self.EXPLICIT_CLARIFICATION_NEGATION.search(compact)):
            return SafetyAssessment("low", "explicit_risk_denial")

        # Answers such as "不知道" and unrelated short replies cannot be treated as
        # a negative response to a direct safety question.
        return SafetyAssessment("uncertain", "ambiguous_clarification_answer")

    @staticmethod
    def is_uncertain_response(text: str) -> bool:
        """Recognize the exact deterministic prompt used to request risk clarification."""
        actual = re.sub(r"\s+", "", text or "")
        expected = re.sub(r"\s+", "", SafetyService.uncertain_response())
        return actual == expected

    @staticmethod
    def is_high_risk_followup_response(text: str) -> bool:
        actual = re.sub(r"\s+", "", text or "")
        expected = re.sub(r"\s+", "", SafetyService._high_risk_followup_response_text())
        return actual.startswith(expected)

    @staticmethod
    def pending_clarification_kind(text: str) -> str | None:
        if SafetyService.is_uncertain_response(text) or SafetyService.is_high_risk_followup_response(text):
            return "risk_intent"
        actual = re.sub(r"\s+", "", text or "")
        expected = re.sub(r"\s+", "", SafetyService._high_risk_response_text())
        if actual.startswith(expected):
            return "safe_location"
        return None

    @classmethod
    def _inside_quote(cls, text: str, position: int) -> bool:
        prefix = text[:position]
        for opening, closing in cls.QUOTE_PAIRS:
            if opening == closing:
                if prefix.count(opening) % 2 == 1:
                    return True
            elif prefix.rfind(opening) > prefix.rfind(closing):
                return True
        return False

    @classmethod
    def _overlaps_quote(cls, text: str, start: int, end: int) -> bool:
        return any(cls._inside_quote(text, position) for position in range(start, end))

    @classmethod
    def _negated_in_context(cls, text: str, start: int, end: int) -> bool:
        context_start = max(cls._clause_start(text, start), start - 24)
        return bool(cls.NEGATED_INTENT_PATTERN.search(text[context_start:end]))

    @classmethod
    def _has_accident_context(cls, text: str, start: int, end: int) -> bool:
        clause_start = cls._clause_start(text, start)
        clause_end = cls._clause_end(text, end)
        clause = text[clause_start:clause_end]
        return any(term in clause for term in cls.ACCIDENT_CONTEXT_TERMS)

    @staticmethod
    def _clause_start(text: str, position: int) -> int:
        boundaries = ("，", ",", "。", ".", "!", "！", "?", "？", "；", ";", "但", "不过", "可是")
        boundary = max((text.rfind(item, 0, position) for item in boundaries), default=-1)
        return boundary + 1

    @staticmethod
    def _clause_end(text: str, position: int) -> int:
        boundaries = ("，", ",", "。", ".", "!", "！", "?", "？", "；", ";", "但", "不过", "可是")
        ends = [text.find(item, position) for item in boundaries if text.find(item, position) >= 0]
        return min(ends, default=len(text))

    def review_response(self, response: str) -> tuple[str, bool]:
        if any(pattern.search(response or "") for pattern in self.UNSAFE_RESPONSE_PATTERNS):
            return self.safe_fallback(), True
        return response, False

    @staticmethod
    def _high_risk_response_text() -> str:
        return (
            "听起来你现在承受着很大的痛苦，我很在意你此刻的安全。"
            "如果你已经准备伤害自己或身边有人正处于危险中，请立即联系当地紧急服务，"
            "并尽快请一位你信任的人陪在你身边。你现在是否处于安全的地方？"
        )

    @staticmethod
    def high_risk_response() -> str:
        return SafetyService._include_crisis_resources(SafetyService._high_risk_response_text())

    @staticmethod
    def uncertain_response() -> str:
        return (
            "我不确定你是在引用、回忆，还是在描述自己现在的处境。"
            "我想先确认：你此刻有没有伤害自己或他人的打算，或者已经采取了行动？"
        )

    @staticmethod
    def _high_risk_followup_response_text() -> str:
        return (
            "谢谢你告诉我。即使你现在处于安全地点，刚才提到的风险仍需要认真对待。"
            "请继续和可信任的人待在一起，并尽快联系当地紧急服务。"
            "你此刻是否仍有伤害自己或他人的打算，或者已经采取了行动？"
        )

    @staticmethod
    def high_risk_followup_response() -> str:
        return SafetyService._include_crisis_resources(
            SafetyService._high_risk_followup_response_text()
        )

    @staticmethod
    def safe_fallback() -> str:
        response = (
            "我不能帮助提供伤害自己或他人的方法，也不能替你做医疗诊断或用药决定。"
            "如果你现在有立即危险，请联系当地紧急服务，并找一位可信任的人陪着你。"
        )
        return SafetyService._include_crisis_resources(response)

    @staticmethod
    def crisis_resource_guidance() -> str:
        """Use only contact details explicitly configured for this deployment."""
        configured_text = os.getenv("CRISIS_RESOURCE_TEXT", "").strip()
        if configured_text:
            return "部署方配置的本地求助资源：\n" + configured_text
        return (
            "当前部署未提供本地核验的专线联系方式。"
            "若你正处于即时危险，请联系当地紧急服务或前往附近急诊。"
        )

    @staticmethod
    def _include_crisis_resources(response: str) -> str:
        if not os.getenv("CRISIS_RESOURCE_TEXT", "").strip():
            return response
        return response + "\n\n" + SafetyService.crisis_resource_guidance()


safety_service = SafetyService()
