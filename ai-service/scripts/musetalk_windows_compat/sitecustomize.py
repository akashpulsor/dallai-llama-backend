"""Compatibility for MuseTalk's older OpenMMLab package guards on Windows."""

try:
    import mmcv

    if mmcv.__version__ == "2.2.0":
        # The official Torch 2.3 Windows wheel supplies the compiled ops that
        # DWPose needs; MuseTalk's pinned MMPose/MMDetection only reject its
        # newer metadata version.
        mmcv.__version__ = "2.0.1"
except ImportError:
    pass
