package com.dalai.llama.critic.service.critique;

import com.dalai.llama.critic.domain.entity.CritiquePlanSnapshot;
import com.dalai.llama.critic.dto.shotcontext.Camera;

/** The only place the flat {@code CritiquePlanSnapshot.cine*} columns are mapped to/from the
 * {@link Camera} DTO's cinematography taxonomy -- mirrors pre-production-service's
 * {@code CinematographyMapper} field-for-field. */
final class CameraSnapshotMapper {

    private CameraSnapshotMapper() {
    }

    static void applyTo(CritiquePlanSnapshot.CritiquePlanSnapshotBuilder builder, Camera c) {
        if (c == null) {
            return;
        }
        builder.cameraShotSize(c.shotSize())
                .cameraNote(c.cameraNote())
                .cineCameraBody(c.cameraBody())
                .cineSensor(c.sensor())
                .cineCaptureFormat(c.captureFormat())
                .cineRecordingCharacteristics(c.recordingCharacteristics())
                .cinePositionHeight(c.positionHeight())
                .cinePositionDistance(c.positionDistance())
                .cinePositionLateral(c.positionLateral())
                .cinePositionElevation(c.positionElevation())
                .cinePositionOrientation(c.positionOrientation())
                .cineLensFocalLength(c.lensFocalLength())
                .cineLensType(c.lensType())
                .cineLensOpticalFormat(c.lensOpticalFormat())
                .cineLensDistortion(c.lensDistortion())
                .cineLensCompression(c.lensCompression())
                .cineLensCharacter(c.lensCharacter())
                .cineFraming(c.framing())
                .cineSubjectPlacement(c.subjectPlacement())
                .cineHeadroom(c.headroom())
                .cineLeadRoom(c.leadRoom())
                .cineVisualBalance(c.visualBalance())
                .cineFocusTarget(c.focusTarget())
                .cineFocusDistance(c.focusDistance())
                .cineDepthOfField(c.depthOfField())
                .cineRackFocus(c.rackFocus())
                .cineFocusBehaviour(c.focusBehaviour())
                .cineMovementType(c.movementType())
                .cineMovementTrajectory(c.movementTrajectory())
                .cineMovementSpeed(c.movementSpeed())
                .cineMovementAcceleration(c.movementAcceleration())
                .cineMovementRotation(c.movementRotation())
                .cineMovementSubjectRelationship(c.movementSubjectRelationship())
                .cineSupport(c.support())
                .cineAperture(c.aperture())
                .cineIso(c.iso())
                .cineShutter(c.shutter())
                .cineNdFilter(c.ndFilter())
                .cineDynamicRange(c.dynamicRange())
                .cineShutterAngle(c.shutterAngle())
                .cineMotionBlur(c.motionBlur())
                .cineSlowMotion(c.slowMotion())
                .cineFiltrationDiffusion(c.filtrationDiffusion())
                .cineFiltrationNd(c.filtrationNd())
                .cineFiltrationPolarizer(c.filtrationPolarizer())
                .cineFiltrationSpecialty(c.filtrationSpecialty())
                .cineContrast(c.contrast())
                .cineColorResponse(c.colorResponse())
                .cineGrain(c.grain())
                .cineHalation(c.halation())
                .cineBloom(c.bloom())
                .cineSharpness(c.sharpness())
                .cineFlare(c.flare());
    }

    static Camera fromSnapshot(CritiquePlanSnapshot s) {
        return new Camera(
                s.getCameraShotSize(), s.getCameraNote(),
                s.getCineCameraBody(), s.getCineSensor(), s.getCineCaptureFormat(), s.getCineRecordingCharacteristics(),
                s.getCinePositionHeight(), s.getCinePositionDistance(), s.getCinePositionLateral(),
                s.getCinePositionElevation(), s.getCinePositionOrientation(),
                s.getCineLensFocalLength(), s.getCineLensType(), s.getCineLensOpticalFormat(),
                s.getCineLensDistortion(), s.getCineLensCompression(), s.getCineLensCharacter(),
                s.getCineFraming(), s.getCineSubjectPlacement(), s.getCineHeadroom(), s.getCineLeadRoom(), s.getCineVisualBalance(),
                s.getCineFocusTarget(), s.getCineFocusDistance(), s.getCineDepthOfField(), s.getCineRackFocus(), s.getCineFocusBehaviour(),
                s.getCineMovementType(), s.getCineMovementTrajectory(), s.getCineMovementSpeed(),
                s.getCineMovementAcceleration(), s.getCineMovementRotation(), s.getCineMovementSubjectRelationship(),
                s.getCineSupport(),
                s.getCineAperture(), s.getCineIso(), s.getCineShutter(), s.getCineNdFilter(), s.getCineDynamicRange(),
                s.getCineShutterAngle(), s.getCineMotionBlur(), s.getCineSlowMotion(),
                s.getCineFiltrationDiffusion(), s.getCineFiltrationNd(), s.getCineFiltrationPolarizer(), s.getCineFiltrationSpecialty(),
                s.getCineContrast(), s.getCineColorResponse(), s.getCineGrain(), s.getCineHalation(),
                s.getCineBloom(), s.getCineSharpness(), s.getCineFlare());
    }
}
