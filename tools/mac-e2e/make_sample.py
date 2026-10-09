"""Synthesise the regression clip: 6 Japanese sentences (macOS `say -v Kyoko`) with 1-2 s gaps, 16 kHz mono.

Sentence 6 is two clauses with only a short breath between them; it is the case that once lost
「おじいさんは山へ」 at a forced cut and that exposed ReazonSpeech dropping the first word of a chunk.
"""
import subprocess, sys, wave, array, os

LINES = [
    "すみません、駅までの道を教えていただけますか。",
    "まっすぐ行って、二つ目の信号を右に曲がってください。",
    "そんなこと、絶対に許さないからな！",
    "まさか、あいつが犯人だったなんて思わなかった。",
    "明日の会議は午後三時からに変更になりました。資料は今日中に送ります。",
    "昔々、ある所におじいさんとおばあさんが住んでいました。おじいさんは山へ芝刈りに、おばあさんは川へ洗濯に行きました。",
]

work, out = sys.argv[1], sys.argv[2]
pcm = array.array("h", [0] * 8000)
for i, s in enumerate(LINES, 1):
    aiff, wav = os.path.join(work, f"l{i}.aiff"), os.path.join(work, f"l{i}.wav")
    subprocess.run(["say", "-v", "Kyoko", "-o", aiff, s], check=True)
    subprocess.run(["afconvert", "-f", "WAVE", "-d", "LEI16@16000", "-c", "1", aiff, wav], check=True)
    with wave.open(wav) as w:
        pcm.frombytes(w.readframes(w.getnframes()))
    pcm.extend([0] * (16000 if i % 2 else 32000))
with wave.open(out, "wb") as w:
    w.setnchannels(1); w.setsampwidth(2); w.setframerate(16000); w.writeframes(pcm.tobytes())
print(f"{out}: {len(pcm) / 16000:.1f}s")
