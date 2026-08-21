package com.dalai.llama.videogen.service;

import com.dalai.llama.videogen.dto.shotcontext.ShotContext;

import java.util.List;

/** Produces the foley/BGM cue sheet -- timestamp + what kind of sound -- for the future
 * post-production service to read. Never generates audio (design doc §4.2/§6). */
public interface FoleyCueService {

    List<DerivedFoleyCue> deriveCues(ShotContext shotContext);
}
