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
