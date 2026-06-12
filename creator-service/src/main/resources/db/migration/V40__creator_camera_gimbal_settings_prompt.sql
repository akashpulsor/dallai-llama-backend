INSERT INTO creator_prompt_templates (template_key, version, name, template_body, metadata)
SELECT
    template_key,
    3,
    name || ' gimbal live movement settings',
    replace(
        replace(
            template_body,
            '- movementSpec uses cameraMovement and stabilization rules.',
            '- movementSpec uses cameraMovement and stabilization rules.
- gimbalSettings is always present. For static shots use enabled=false, device="none", mode="locked_off". For live camera movement provide gimbal device, mode, axisLock, panSpeed, tiltSpeed, deadband, followDurationSeconds, horizonLock, stabilizationStrength, operatorPath, and rehearsalCue.'
        ),
        '  "movementSpec": {"moveType": "Static", "startPosition": "", "endPosition": "", "speed": "", "stabilizationRequired": false, "stabilizationTool": "", "rigType": ""},
  "coverageSpec":',
        '  "movementSpec": {"moveType": "Static", "startPosition": "", "endPosition": "", "speed": "", "stabilizationRequired": false, "stabilizationTool": "", "rigType": "", "liveCameraMove": false, "operatorCue": ""},
  "gimbalSettings": {"enabled": false, "device": "", "mode": "locked_off", "axisLock": "pan_tilt_locked", "panSpeed": 0.0, "tiltSpeed": 0.0, "deadband": 0.0, "followDurationSeconds": 0.0, "horizonLock": true, "stabilizationStrength": "", "operatorPath": "", "rehearsalCue": ""},
  "coverageSpec":'
    ),
    metadata || jsonb_build_object('gimbalSettings', true)
FROM creator_prompt_templates
WHERE template_key = 'CAMERA_PLAN_SHEET_TAG_GENERATE'
  AND version = 2
ON CONFLICT (template_key, version) DO NOTHING;
