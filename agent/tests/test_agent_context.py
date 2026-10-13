from datetime import date, timedelta

from tools.mental_health_tools import CURRENT_AGENT_CONTEXT, get_emotion_history, get_user_memories


def test_emotion_history_uses_only_supplied_user_records():
    today = date(2026, 10, 13)
    token = CURRENT_AGENT_CONTEXT.set({
        "asOfDate": today.isoformat(),
        "emotionHistory": [
            {"diaryDate": today.isoformat(), "dominantEmotion": "焦虑", "moodScore": 4, "sleepQuality": 2},
            {"diaryDate": (today - timedelta(days=2)).isoformat(), "dominantEmotion": "平静", "moodScore": 7, "sleepQuality": 4},
            {"diaryDate": (today - timedelta(days=8)).isoformat(), "dominantEmotion": "悲伤", "moodScore": 2, "sleepQuality": 1},
        ],
    })
    try:
        result = get_emotion_history.invoke({"days": 7})
    finally:
        CURRENT_AGENT_CONTEXT.reset(token)

    assert "2篇" in result
    assert "焦虑 1篇" in result
    assert "平静 1篇" in result
    assert "悲伤" not in result
    assert "自评" in result


def test_emotion_history_does_not_invent_example_data_when_context_is_empty():
    token = CURRENT_AGENT_CONTEXT.set({"emotionHistory": []})
    try:
        result = get_emotion_history.invoke({"days": 7})
    finally:
        CURRENT_AGENT_CONTEXT.reset(token)

    assert "没有可用" in result
    assert "焦虑 4 天" not in result


def test_user_memories_are_unavailable_without_explicit_personalization_consent():
    token = CURRENT_AGENT_CONTEXT.set({
        "personalizationEnabled": False,
        "approvedMemories": [{"content": "私密偏好"}],
    })
    try:
        result = get_user_memories.invoke({})
    finally:
        CURRENT_AGENT_CONTEXT.reset(token)

    assert "未开启" in result
    assert "私密偏好" not in result


def test_user_memory_tool_omits_expired_entries_and_keeps_source_context():
    today = date(2026, 10, 13)
    token = CURRENT_AGENT_CONTEXT.set({
        "asOfDate": today.isoformat(),
        "personalizationEnabled": True,
        "approvedMemories": [
            {"content": "喜欢简短的建议", "source": "用户主动添加", "updatedAt": today.isoformat(), "expiresAt": None},
            {"content": "已过期的内容", "expiresAt": (today - timedelta(days=1)).isoformat()},
        ],
    })
    try:
        result = get_user_memories.invoke({})
    finally:
        CURRENT_AGENT_CONTEXT.reset(token)

    assert "喜欢简短的建议" in result
    assert "用户主动添加" in result
    assert "已过期的内容" not in result
