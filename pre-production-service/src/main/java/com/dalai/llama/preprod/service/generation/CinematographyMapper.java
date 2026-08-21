package com.dalai.llama.preprod.service.generation;

import com.dalai.llama.preprod.domain.entity.Shot;
import com.dalai.llama.preprod.dto.CinematographyView;

/** The only place the flat {@code Shot.cine*} columns are mapped to/from the cinematography
 * taxonomy's field names -- both {@link ShotListGenerationResult.CinematographyItem} (LLM
 * response) and {@link CinematographyView} (API response) mirror this field set exactly, so
 * adding a new taxonomy field means updating this class and nowhere else. */
public final class CinematographyMapper {

    private CinematographyMapper() {
    }

    public static void applyTo(Shot shot, ShotListGenerationResult.CinematographyItem c) {
        if (c == null) {
            return;
        }
        shot.setCineCameraBody(c.cameraBody());
        shot.setCineSensor(c.sensor());
        shot.setCineCaptureFormat(c.captureFormat());
        shot.setCineRecordingCharacteristics(c.recordingCharacteristics());
        shot.setCinePositionHeight(c.positionHeight());
        shot.setCinePositionDistance(c.positionDistance());
        shot.setCinePositionLateral(c.positionLateral());
        shot.setCinePositionElevation(c.positionElevation());
        shot.setCinePositionOrientation(c.positionOrientation());
        shot.setCineLensFocalLength(c.lensFocalLength());
        shot.setCineLensType(c.lensType());
        shot.setCineLensOpticalFormat(c.lensOpticalFormat());
        shot.setCineLensDistortion(c.lensDistortion());
        shot.setCineLensCompression(c.lensCompression());
        shot.setCineLensCharacter(c.lensCharacter());
        shot.setCineFraming(c.framing());
        shot.setCineSubjectPlacement(c.subjectPlacement());
        shot.setCineHeadroom(c.headroom());
        shot.setCineLeadRoom(c.leadRoom());
        shot.setCineVisualBalance(c.visualBalance());
        shot.setCineFocusTarget(c.focusTarget());
        shot.setCineFocusDistance(c.focusDistance());
        shot.setCineDepthOfField(c.depthOfField());
        shot.setCineRackFocus(c.rackFocus());
        shot.setCineFocusBehaviour(c.focusBehaviour());
        shot.setCineMovementType(c.movementType());
        shot.setCineMovementTrajectory(c.movementTrajectory());
        shot.setCineMovementSpeed(c.movementSpeed());
        shot.setCineMovementAcceleration(c.movementAcceleration());
        shot.setCineMovementRotation(c.movementRotation());
        shot.setCineMovementSubjectRelationship(c.movementSubjectRelationship());
        shot.setCineSupport(c.support());
        shot.setCineAperture(c.aperture());
        shot.setCineIso(c.iso());
        shot.setCineShutter(c.shutter());
        shot.setCineNdFilter(c.ndFilter());
        shot.setCineDynamicRange(c.dynamicRange());
        shot.setCineShutterAngle(c.shutterAngle());
        shot.setCineMotionBlur(c.motionBlur());
        shot.setCineSlowMotion(c.slowMotion());
        shot.setCineFiltrationDiffusion(c.filtrationDiffusion());
        shot.setCineFiltrationNd(c.filtrationNd());
        shot.setCineFiltrationPolarizer(c.filtrationPolarizer());
        shot.setCineFiltrationSpecialty(c.filtrationSpecialty());
        shot.setCineContrast(c.contrast());
        shot.setCineColorResponse(c.colorResponse());
        shot.setCineGrain(c.grain());
        shot.setCineHalation(c.halation());
        shot.setCineBloom(c.bloom());
        shot.setCineSharpness(c.sharpness());
        shot.setCineFlare(c.flare());
    }

    public static CinematographyView toView(Shot shot) {
        return new CinematographyView(
                shot.getCineCameraBody(), shot.getCineSensor(), shot.getCineCaptureFormat(), shot.getCineRecordingCharacteristics(),
                shot.getCinePositionHeight(), shot.getCinePositionDistance(), shot.getCinePositionLateral(),
                shot.getCinePositionElevation(), shot.getCinePositionOrientation(),
                shot.getCineLensFocalLength(), shot.getCineLensType(), shot.getCineLensOpticalFormat(),
                shot.getCineLensDistortion(), shot.getCineLensCompression(), shot.getCineLensCharacter(),
                shot.getCineFraming(), shot.getCineSubjectPlacement(), shot.getCineHeadroom(), shot.getCineLeadRoom(), shot.getCineVisualBalance(),
                shot.getCineFocusTarget(), shot.getCineFocusDistance(), shot.getCineDepthOfField(), shot.getCineRackFocus(), shot.getCineFocusBehaviour(),
                shot.getCineMovementType(), shot.getCineMovementTrajectory(), shot.getCineMovementSpeed(),
                shot.getCineMovementAcceleration(), shot.getCineMovementRotation(), shot.getCineMovementSubjectRelationship(),
                shot.getCineSupport(),
                shot.getCineAperture(), shot.getCineIso(), shot.getCineShutter(), shot.getCineNdFilter(), shot.getCineDynamicRange(),
                shot.getCineShutterAngle(), shot.getCineMotionBlur(), shot.getCineSlowMotion(),
                shot.getCineFiltrationDiffusion(), shot.getCineFiltrationNd(), shot.getCineFiltrationPolarizer(), shot.getCineFiltrationSpecialty(),
                shot.getCineContrast(), shot.getCineColorResponse(), shot.getCineGrain(), shot.getCineHalation(),
                shot.getCineBloom(), shot.getCineSharpness(), shot.getCineFlare());
    }
}
