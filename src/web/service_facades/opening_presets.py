from __future__ import annotations

from typing import Any

from src.web.time_utils import utc_now as _utc_now
from src.web.review import (
    delete_opening_preset_payload,
    list_opening_presets_payload,
    load_opening_preset_payload,
    save_opening_preset_payload,
)



class OpeningPresetServiceMixin:
    def list_opening_presets(self) -> list[dict[str, Any]]:
        return list_opening_presets_payload(self.opening_presets_root)

    def get_opening_preset(self, card_id: str) -> dict[str, Any]:
        return load_opening_preset_payload(self.opening_presets_root, card_id)

    def save_opening_preset(self, *, card_id: str = "", fields: dict[str, Any]) -> dict[str, Any]:
        payload = save_opening_preset_payload(
            self.opening_presets_root,
            card_id=card_id,
            fields=fields,
            utc_now=_utc_now,
        )
        return self.get_opening_preset(payload["card_id"])

    def delete_opening_preset(self, card_id: str) -> dict[str, str]:
        return delete_opening_preset_payload(self.opening_presets_root, card_id)
