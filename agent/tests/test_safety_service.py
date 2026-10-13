from services.safety_service import SafetyService


def test_direct_current_self_harm_plan_is_high_risk():
    result = SafetyService().assess("我今晚准备结束生命")

    assert result.level == "high"
    assert "安全" in SafetyService.high_risk_response()


def test_negated_or_quoted_reference_is_uncertain_not_high():
    safety = SafetyService()

    assert safety.assess("我没有自伤打算").level == "uncertain"
    assert safety.assess("文章里写着‘我要自杀’").level == "uncertain"


def test_regular_distress_does_not_trigger_crisis_gate():
    assert SafetyService().assess("我最近因为工作很焦虑").level == "low"


def test_unsafe_generated_instructions_are_replaced():
    response, blocked = SafetyService().review_response("以下是如何自残的具体方法")

    assert blocked is True
    assert "不能帮助提供" in response


def test_affirmative_answer_to_pending_risk_question_is_high():
    result = SafetyService().assess_clarification("有")

    assert result.level == "high"
    assert result.reason == "affirmative_risk_confirmation"


def test_ambiguous_answer_to_pending_risk_question_stays_uncertain():
    result = SafetyService().assess_clarification("我不知道")

    assert result.level == "uncertain"
    assert result.reason == "ambiguous_clarification_answer"


def test_explicit_denial_resolves_pending_risk_question():
    result = SafetyService().assess_clarification("我没有伤害自己的打算")

    assert result.level == "low"
    assert result.reason == "explicit_risk_denial"


def test_safe_location_answer_does_not_clear_a_previously_high_risk_turn():
    safety = SafetyService()

    assert safety.assess_clarification("是", "safe_location").level == "uncertain"
    assert safety.assess_clarification("没有", "safe_location").level == "high"
