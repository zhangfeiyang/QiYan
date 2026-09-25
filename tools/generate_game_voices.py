import asyncio
import os
import edge_tts

VOICES_DIR = "/home/zhangfy/zhongguoxiangqi/tools/raw_voices"
os.makedirs(VOICES_DIR, exist_ok=True)

phrases = {
    "voice_check": ("将军！", "+5%", "+0Hz"),
    "voice_checkmate": ("绝杀！", "+5%", "+2Hz"),
    "voice_win": ("大获全胜！", "+0%", "+1Hz"),
    "voice_loss": ("棋差一着，惜败。", "-5%", "-1Hz"),
    "voice_draw": ("握手言和。", "-5%", "-1Hz"),
    "voice_tactic_mahoupao": ("马后炮！", "+5%", "+1Hz"),
    "voice_tactic_tiemenshuan": ("铁门栓！", "+5%", "+1Hz"),
    "voice_tactic_wochaoma": ("卧槽马！", "+5%", "+1Hz"),
    "voice_tactic_shuangche": ("双车错！", "+5%", "+1Hz"),
    "voice_tactic_zhongpao": ("重炮！", "+5%", "+1Hz"),
    "voice_eat": ("吃！", "+8%", "+1Hz"),
    # 2026-09 polish pass: 开局 / 残局答对答错 / 闯关过关
    "voice_game_start": ("红方先行。", "+0%", "+0Hz"),
    "voice_puzzle_solved": ("妙手！", "+5%", "+1Hz"),
    "voice_puzzle_fail": ("再来一次。", "-3%", "-1Hz"),
    "voice_campaign_clear": ("过关！", "+5%", "+1Hz"),
}

async def generate():
    # 已有产物默认跳过 (保持已入库音频稳定); FORCE=1 全量重新生成.
    force = os.environ.get("FORCE") == "1"
    for name, (text, rate, pitch) in phrases.items():
        out_path = f"{VOICES_DIR}/{name}.mp3"
        if os.path.exists(out_path) and not force:
            print(f"Skip {name} (exists)")
            continue
        print(f"Generating {name}: {text}...")
        comm = edge_tts.Communicate(text, voice="zh-CN-YunjianNeural", rate=rate, pitch=pitch)
        await comm.save(out_path)
    print("All voices generated successfully!")

if __name__ == "__main__":
    asyncio.run(generate())
