from __future__ import annotations

from typing import Any

from pydantic import BaseModel, Field

try:
    from pydantic import field_validator
except ImportError:  # Pydantic v1 on Termux
    from pydantic import validator as field_validator

    _pydantic_v1_validator = field_validator

    def field_validator(*fields: str, **kwargs):  # type: ignore[no-redef]
        # Embedded Android can restart Python and import this module again in
        # the same process. Pydantic v1 otherwise treats the same validator
        # function as a duplicate registration.
        kwargs.setdefault("allow_reuse", True)
        return _pydantic_v1_validator(*fields, **kwargs)


class CreateRunRequest(BaseModel):
    novel_name: str = Field(..., min_length=1)
    novel_content_base64: str = Field(..., min_length=1)
    characters: list[str] = Field(default_factory=list)
    max_sentences: int = Field(default=120, ge=20, le=300)
    max_chars: int = Field(default=50_000, ge=2_000, le=200_000)
    auto_run: bool = Field(default=False)
    defer_run: bool = Field(default=False)

    @field_validator("characters")
    @classmethod
    def _validate_characters(cls, value: list[str]) -> list[str]:
        if not value:
            raise ValueError("characters must not be empty")
        return value


class EstimateSamplingRequest(BaseModel):
    char_count: int = Field(..., ge=1, le=24_000_000)
    sentence_count: int = Field(..., ge=1, le=5_000_000)
    character_count: int = Field(..., ge=1, le=100)
    max_sentences: int = Field(default=120, ge=20, le=300)
    max_chars: int = Field(default=50_000, ge=2_000, le=200_000)


class RestartRunRequest(BaseModel):
    characters: list[str] = Field(default_factory=list)
    novel_name: str = Field(default="")
    novel_content_base64: str = Field(default="")
    max_sentences: int = Field(default=120, ge=20, le=300)
    max_chars: int = Field(default=50_000, ge=2_000, le=200_000)


class SuggestRedistillSegmentsRequest(BaseModel):
    character: str = Field(..., min_length=1)
    max_segments: int = Field(default=3, ge=1, le=8)


class SaveModelSettingsRequest(BaseModel):
    provider: str = Field(..., min_length=1)
    model: str = Field(..., min_length=1)
    base_url: str = Field(default="")
    api_key: str = Field(default="")
    max_tokens: int = Field(default=0, ge=0, le=16000)
    reasoning_effort: str = Field(default="off", max_length=16)
    profile_id: str = Field(default="")
    profile_name: str = Field(default="", max_length=80)
    create_profile: bool = Field(default=False)
    activate_profile: bool = Field(default=True)


class TestModelSettingsRequest(BaseModel):
    provider: str = Field(..., min_length=1)
    model: str = Field(..., min_length=1)
    base_url: str = Field(default="")
    api_key: str = Field(default="")
    max_tokens: int = Field(default=0, ge=0, le=16000)
    reasoning_effort: str = Field(default="off", max_length=16)
    profile_id: str = Field(default="")


class StartAppUpdateRequest(BaseModel):
    confirm: str = Field(..., min_length=1)


class IngestCharacterRequest(BaseModel):
    character: str = Field(..., min_length=1)
    content_base64: str = Field(..., min_length=1)
    filename: str = Field(default="PROFILE.generated.md")


class IngestRelationRequest(BaseModel):
    content_base64: str = Field(..., min_length=1)
    filename: str = Field(default="relations.md")


class ImportRunPackageRequest(BaseModel):
    filename: str = Field(..., min_length=1)
    content_base64: str = Field(..., min_length=1)
    library_package: dict[str, str] = Field(default_factory=dict)


class ShareRunPackageRequest(BaseModel):
    include_dialogue: bool = Field(default=True)


class CrossoverParticipantRequest(BaseModel):
    run_id: str = Field(..., min_length=1)
    character: str = Field(..., min_length=1)


class CreateCrossoverSpaceRequest(BaseModel):
    title: str = Field(..., min_length=1, max_length=80)
    world_setting: str = Field(default="", max_length=1000)
    participants: list[CrossoverParticipantRequest] = Field(...)

    @field_validator("participants")
    @classmethod
    def _validate_participants(
        cls, value: list[CrossoverParticipantRequest]
    ) -> list[CrossoverParticipantRequest]:
        if not 2 <= len(value) <= 8:
            raise ValueError("participants must contain between 2 and 8 items")
        return value


class SavePersonaReviewRequest(BaseModel):
    core_identity: str = Field(default="")
    story_role: str = Field(default="")
    identity_anchor: str = Field(default="")
    gender: str = Field(default="")
    age_stage: str = Field(default="")
    appearance_feature: str = Field(default="")
    habit_action: str = Field(default="")
    preference_like: str = Field(default="")
    dislike_hate: str = Field(default="")
    temperament_type: str = Field(default="")
    soul_goal: str = Field(default="")
    hidden_desire: str = Field(default="")
    inner_conflict: str = Field(default="")
    self_cognition: str = Field(default="")
    private_self: str = Field(default="")
    speech_style: str = Field(default="")
    cadence: str = Field(default="")
    typical_lines: str = Field(default="")
    signature_phrases: str = Field(default="")
    sentence_openers: str = Field(default="")
    sentence_endings: str = Field(default="")
    social_mode: str = Field(default="")
    thinking_style: str = Field(default="")
    decision_rules: str = Field(default="")
    reward_logic: str = Field(default="")
    worldview: str = Field(default="")
    belief_anchor: str = Field(default="")
    moral_bottom_line: str = Field(default="")
    restraint_threshold: str = Field(default="")
    core_traits: str = Field(default="")
    key_bonds: str = Field(default="")
    forbidden_behaviors: str = Field(default="")
    stress_response: str = Field(default="")
    emotion_model: str = Field(default="")
    anger_style: str = Field(default="")
    joy_style: str = Field(default="")
    grievance_style: str = Field(default="")
    others_impression: str = Field(default="")
    review_source: str = Field(default="")
    review_note: str = Field(default="")


class SuggestPersonaFieldRequest(BaseModel):
    field: str = Field(..., min_length=1)


class SessionRef(BaseModel):
    run_id: str = Field(..., min_length=1)
    session_id: str = Field(..., min_length=1)


class DeleteSessionsRequest(BaseModel):
    items: list[SessionRef] = Field(...)

    @field_validator("items")
    @classmethod
    def _validate_items(cls, value: list[SessionRef]) -> list[SessionRef]:
        if not value:
            raise ValueError("items must not be empty")
        return value


class UpdateRelationDetailRequest(BaseModel):
    trust: int | None = Field(default=None, ge=0, le=10)
    affection: int | None = Field(default=None, ge=0, le=10)
    hostility: int | None = Field(default=None, ge=0, le=10)
    ambiguity: int | None = Field(default=None, ge=0, le=10)
    relationship_type: str = Field(default="")
    relation_change: str = Field(default="")
    conflict_point: str = Field(default="")
    typical_interaction: str = Field(default="")


class CreateDialogueSessionRequest(BaseModel):
    mode: str = Field(default="observe")
    participants: list[str] = Field(default_factory=list)
    controlled_character: str = Field(default="")
    scene_card_id: str = Field(default="")
    scene_profile: dict[str, str] = Field(default_factory=dict)
    self_card_id: str = Field(default="")
    self_profile: dict[str, str] = Field(default_factory=dict)


class SaveSceneCardRequest(BaseModel):
    title: str = Field(default="")
    time_hint: str = Field(default="")
    location: str = Field(default="")
    atmosphere: str = Field(default="")
    opening_situation: str = Field(default="")
    public_goal: str = Field(default="")
    hidden_tension: str = Field(default="")
    scene_drive: str = Field(default="")
    expected_rhythm: str = Field(default="")
    forbidden_topics: str = Field(default="")


class RecommendSceneCardRequest(BaseModel):
    mode: str = Field(default="observe")
    participants: list[str] = Field(default_factory=list)


class SaveOpeningPresetRequest(BaseModel):
    title: str = Field(default="")
    note: str = Field(default="")
    mode: str = Field(default="observe")
    participants: list[str] = Field(default_factory=list)
    controlled_character: str = Field(default="")
    scene_card_id: str = Field(default="")
    scene_card: dict[str, object] = Field(default_factory=dict)
    self_card_id: str = Field(default="")
    self_card: dict[str, object] = Field(default_factory=dict)
    self_name: str = Field(default="")
    self_identity: str = Field(default="")
    self_style: str = Field(default="")


class SaveSelfCardRequest(BaseModel):
    display_name: str = Field(default="")
    scene_identity: str = Field(default="")
    interaction_style: str = Field(default="")
    core_identity: str = Field(default="")
    story_role: str = Field(default="")
    identity_anchor: str = Field(default="")
    gender: str = Field(default="")
    age_stage: str = Field(default="")
    appearance_feature: str = Field(default="")
    habit_action: str = Field(default="")
    preference_like: str = Field(default="")
    dislike_hate: str = Field(default="")
    temperament_type: str = Field(default="")
    soul_goal: str = Field(default="")
    hidden_desire: str = Field(default="")
    inner_conflict: str = Field(default="")
    self_cognition: str = Field(default="")
    private_self: str = Field(default="")
    speech_style: str = Field(default="")
    cadence: str = Field(default="")
    typical_lines: str = Field(default="")
    signature_phrases: str = Field(default="")
    sentence_openers: str = Field(default="")
    sentence_endings: str = Field(default="")
    social_mode: str = Field(default="")
    thinking_style: str = Field(default="")
    decision_rules: str = Field(default="")
    reward_logic: str = Field(default="")
    worldview: str = Field(default="")
    belief_anchor: str = Field(default="")
    moral_bottom_line: str = Field(default="")
    restraint_threshold: str = Field(default="")
    core_traits: str = Field(default="")
    key_bonds: str = Field(default="")
    forbidden_behaviors: str = Field(default="")
    stress_response: str = Field(default="")
    emotion_model: str = Field(default="")
    anger_style: str = Field(default="")
    joy_style: str = Field(default="")
    grievance_style: str = Field(default="")
    others_impression: str = Field(default="")


class PrepareDialogueTurnRequest(BaseModel):
    message: str = Field(..., min_length=1)
    message_kind: str = Field(default="dialogue")
    suppress_transcript_message: bool = Field(default=False)
    operation_id: str = Field(default="", max_length=128)
    include_inner_thoughts: bool = Field(default=False)
    include_model_reasoning: bool = Field(default=False)


class SuggestDialogueTurnRequest(BaseModel):
    seed_text: str = Field(default="")
    direction: str = Field(default="", max_length=240)


class InvokePluginChatActionRequest(BaseModel):
    seed_text: str = Field(default="")
    direction: str = Field(default="", max_length=240)


class InvokeTemporaryNpcGeneratorRequest(BaseModel):
    direction: str = Field(default="", max_length=240)


class SetGenerationEnhancerStateRequest(BaseModel):
    enabled: bool


class InspectPluginPackageRequest(BaseModel):
    filename: str = Field(default="plugin.zip", max_length=200)
    content_base64: str = Field(..., min_length=1)


class InstallPluginPackageRequest(BaseModel):
    confirm_permissions: bool = Field(default=False)
    allow_update: bool = Field(default=False)


class UpdatePluginConfigRequest(BaseModel):
    config: dict[str, Any] = Field(default_factory=dict)


class DialogueAssociationsRequest(BaseModel):
    option_count: int = Field(default=3, ge=2, le=4)


class DialogueDirectorRequest(BaseModel):
    goal: str = Field(..., min_length=1, max_length=240)
    action: str = Field(default="advance")
    option_count: int = Field(default=3, ge=2, le=4)


class SwitchDialogueSceneCardRequest(BaseModel):
    scene_card_id: str = Field(default="")
    scene_profile: dict[str, str] = Field(default_factory=dict)
    transition_message: str = Field(default="")
    auto_continue: bool = Field(default=False)


class BranchDialogueSessionRequest(BaseModel):
    scene_index: int = Field(default=0, ge=0)


class BranchDialogueTurnRequest(BaseModel):
    turn_id: str = Field(..., min_length=1)


class UpdateDialogueBranchMetaRequest(BaseModel):
    label: str | None = Field(default=None, max_length=80)
    is_mainline: bool | None = Field(default=None)
    locked_event_ids: list[str] | None = Field(default=None, max_length=100)


class UpdateDialogueRelationLockRequest(BaseModel):
    pair_key: str = Field(..., min_length=1)
    locked: bool = Field(default=True)


class UpsertDialogueMemoryRequest(BaseModel):
    text: str = Field(..., min_length=1, max_length=500)
    category: str = Field(default="story")
    pinned: bool = Field(default=False)
    enabled: bool = Field(default=True)


class DialogueResponseItem(BaseModel):
    speaker: str = Field(..., min_length=1)
    message: str = Field(..., min_length=1)


class IngestDialogueTurnRequest(BaseModel):
    responses: list[DialogueResponseItem] = Field(default_factory=list)

    @field_validator("responses")
    @classmethod
    def _validate_responses(
        cls, value: list[DialogueResponseItem]
    ) -> list[DialogueResponseItem]:
        if not value:
            raise ValueError("responses must not be empty")
        return value


class SaveChapterRequest(BaseModel):
    title: str = Field(..., min_length=1, max_length=120)
    goal: str = Field(default="", max_length=800)
    participants: list[str] = Field(default_factory=list, max_length=40)
    content: str = Field(default="", max_length=300_000)


class ArchiveDialogueChapterRequest(BaseModel):
    session_id: str = Field(..., min_length=1)
    title: str = Field(default="", max_length=120)


class ReorderChapterRequest(BaseModel):
    target_order: int = Field(..., ge=1)


class AskBookQuestionRequest(BaseModel):
    question: str = Field(..., min_length=1, max_length=300)


class SaveWorldFactRequest(BaseModel):
    category: str = Field(default="event", max_length=40)
    summary: str = Field(..., min_length=1, max_length=500)
    characters: list[str] = Field(default_factory=list, max_length=20)
    location: str = Field(default="", max_length=100)
    time_hint: str = Field(default="", max_length=80)
    locked: bool = Field(default=False)
    active: bool = Field(default=True)


class SearchOriginalKnowledgeRequest(BaseModel):
    query: str = Field(..., min_length=1, max_length=500)
    participants: list[str] = Field(default_factory=list, max_length=20)
    limit: int = Field(default=6, ge=1, le=10)


class UpdateOriginalKnowledgeBoundaryRequest(BaseModel):
    visibility: str = Field(..., min_length=1, max_length=20)
    knowers: list[str] = Field(default_factory=list, max_length=20)
