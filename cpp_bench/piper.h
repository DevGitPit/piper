#ifndef PIPER_H_
#define PIPER_H_

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include <uchar.h>

#ifdef __cplusplus
extern "C" {
#endif

#define PIPER_OK 0
#define PIPER_DONE 1
#define PIPER_ERR_GENERIC -1

/**
 * \brief Text-to-speech synthesizer.
 */
typedef struct piper_synthesizer piper_synthesizer;

/**
 * \brief Chunk of synthesized audio samples.
 */
typedef struct piper_audio_chunk {
  /**
   * \brief Raw samples returned from the voice model.
   */
  const float *samples;

  /**
   * \brief Number of samples in the audio chunk.
   */
  size_t num_samples;

  /**
   * \brief Sample rate in Hertz.
   */
  int sample_rate;

  /**
   * \brief True if this is the last audio chunk.
   */
  bool is_last;

  /**
   * \brief Phoneme codepoints that produced this audio chunk, aligned with ids.
   */
  const char32_t *phonemes;

  /**
   * \brief Number of codepoints in phonemes.
   */
  size_t num_phonemes;

  /**
   * \brief Phoneme ids that produced this audio chunk.
   */
  const int *phoneme_ids;

  /**
   * \brief Number of ids in phoneme_ids.
   */
  size_t num_phoneme_ids;

  /**
   * \brief Audio sample count for each phoneme id.
   */
  const int *alignments;

  /**
   * \brief Number of alignments.
   */
  size_t num_alignments;
} piper_audio_chunk;

/**
 * \brief Options for synthesis.
 */
typedef struct piper_synthesize_options {
  /**
   * \brief Id of speaker to use (multi-speaker models only).
   */
  int speaker_id;

  /**
   * \brief How fast the text is spoken.
   */
  float length_scale;

  /**
   * \brief Controls how much noise is added during synthesis.
   */
  float noise_scale;

  /**
   * \brief Controls how much phonemes vary in length during synthesis.
   */
  float noise_w_scale;
} piper_synthesize_options;

piper_synthesizer *piper_create(const char *model_path, const char *config_path,
                                const char *espeak_data_path, int threads);

void piper_free(piper_synthesizer *synth);

piper_synthesize_options
piper_default_synthesize_options(piper_synthesizer *synth);

int piper_synthesize_start(piper_synthesizer *synth, const char *text,
                           const piper_synthesize_options *options);

int piper_synthesize_next(piper_synthesizer *synth, piper_audio_chunk *chunk);

#ifdef __cplusplus
}
#endif

#endif // PIPER_H_
