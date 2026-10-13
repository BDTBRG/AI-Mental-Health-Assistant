"""Deterministic pre- and post-generation safety gates for mental-health chat."""

import re
from dataclasses import dataclass
from typing import Literal


SafetyLevel = Literal["low", "uncertain", "high"]


@dataclass(frozen=True)
class SafetyAssessment:
    level: SafetyLevel
    reason: str


class SafetyService:
    HIGH_INTENT_PATTERNS = (
        re.compile(r"(?:我|自己|本人).{0,8}(?:今晚|今天|现在|马上|准备|打算|计划|想要|要).{0,12}(?:自杀|轻生|结束生命|伤害自己|自残|割腕|跳楼|吞药)"),
        re.compile(r"(?:已经|正在).{0,8}(?:割腕|吞药|跳楼|自残|伤害自己)"),
        re.compile(r"(?:我|自己|本人).{0,8}(?:想杀|准备杀|要杀|伤害).{0,12}(?:他|她|别人|同事|家人|自己)"),
    )
    NEGATED_INTENT_PATTERN = re.compile(
        r"(?:没有|没|不|未|不会|从未|并非|不是).{0,12}"
        r"(?:准备|打算|计划|想要|要|自杀|轻生|结束生命|伤害自己|自伤|自残|割腕|跳楼|吞药)"
    )
    QUOTE_PAIRS = (("“", "”"), ("‘", "’"), ("「", "」"), ("『", "』"), ('"', '"'), ("'", "'"))
    UNCERTAIN_TERMS = (
        "自杀", "轻生", "结束生命", "不想活", "自伤", "自残", "割腕", "跳楼", "吞药", "伤害自己",
        "杀死", "杀人", "伤害别人",
    )
    UNSAFE_RESPONSE_PATTERNS = (
        re.compile(r"(?:如何|怎样|步骤|方法|剂量).{0,24}(?:自杀|轻生|自残|割腕|吞药|跳楼)"),
        re.compile(r"(?:建议|推荐|可以).{0,16}(?:停药|加量|减量|换药)"),
        re.compile(r"(?:你患有|诊断为|你就是).{0,12}(?:抑郁症|焦虑症|双相障碍|精神分裂症)"),
    )

    def assess(self, text: str) -> SafetyAssessment:
        normalized = re.sub(r"\s+", "", text or "").lower()
        if not normalized:
            return SafetyAssessment("low", "empty_input")

        for pattern in self.HIGH_INTENT_PATTERNS:
            match = pattern.search(normalized)
            if match:
                if self._inside_quote(normalized, match.start()) or self.NEGATED_INTENT_PATTERN.search(match.group()):
                    return SafetyAssessment("uncertain", "quoted_or_negated_harm_reference")
                return SafetyAssessment("high", "direct_harm_intent")

        if any(term in normalized for term in self.UNCERTAIN_TERMS):
            return SafetyAssessment("uncertain", "harm_reference_requires_clarification")

        return SafetyAssessment("low", "no_rule_match")

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

    def review_response(self, response: str) -> tuple[str, bool]:
        if any(pattern.search(response or "") for pattern in self.UNSAFE_RESPONSE_PATTERNS):
            return self.safe_fallback(), True
        return response, False

    @staticmethod
    def high_risk_response() -> str:
        return (
            "听起来你现在承受着很大的痛苦，我很在意你此刻的安全。"
            "如果你已经准备伤害自己或身边有人正处于危险中，请立即联系当地紧急服务，"
            "并尽快请一位你信任的人陪在你身边。你现在是否处于安全的地方？"
        )

    @staticmethod
    def uncertain_response() -> str:
        return (
            "我不确定你是在引用、回忆，还是在描述自己现在的处境。"
            "我想先确认：你此刻有没有伤害自己或他人的打算，或者已经采取了行动？"
        )

    @staticmethod
    def safe_fallback() -> str:
        return (
            "我不能帮助提供伤害自己或他人的方法，也不能替你做医疗诊断或用药决定。"
            "如果你现在有立即危险，请联系当地紧急服务，并找一位可信任的人陪着你。"
        )


safety_service = SafetyService()
