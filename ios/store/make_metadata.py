#!/usr/bin/env python3
"""docs/play-listing-1.4.0.json (16 Play locales) -> ios/store/metadata.json for App Store Connect.

The long description is the Play text with the Android wording removed and the
"open source & privacy" paragraph replaced: the iOS app has no AI chat and never
touches the network. Name / subtitle / keywords are App Store specific.
"""
import json
import os
import re

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.join(HERE, "..", "..")
PLAY = json.load(open(os.path.join(ROOT, "docs", "play-listing-1.4.0.json"), encoding="utf-8"))

# Play locale -> (ASC locale, name, subtitle, keywords, privacy paragraph)
L = {
    "zh-CN": ("zh-Hans", "棋眼", "中国象棋对弈·拍照识局·复盘",
              "象棋,中国象棋,下象棋,象棋残局,象棋陪练,象棋复盘,杀法,拍照识棋,象棋引擎,单机象棋,象棋教学,棋谱",
              "【开源与隐私】整个应用以 GPL-3 协议开源（github.com/zhangfeiyang/QiYan），完全免费，没有广告、统计 SDK 和账号。所有功能都在 iPhone 本地运行，不联网，不收集任何数据。"),
    "zh-TW": ("zh-Hant", "棋眼 QiYan 中國象棋", "中國象棋對弈·拍照識局·覆盤",
              "象棋,中國象棋,下象棋,象棋殘局,象棋陪練,覆盤,殺法,拍照識棋,象棋引擎,單機象棋,棋譜",
              "【開源與隱私】整個應用以 GPL-3 協議開源（github.com/zhangfeiyang/QiYan），完全免費，沒有廣告、統計 SDK 與帳號。所有功能都在 iPhone 本機運行，不連網，不收集任何資料。"),
    "en-US": ("en-US", "QiYan Xiangqi Chinese Chess", "Play, scan boards & review",
              "xiangqi,chinese chess,board game,strategy,puzzle,endgame,offline,engine,training,elephant chess",
              "【Open source & privacy】The whole app is open source under GPL-3 (github.com/zhangfeiyang/QiYan) and completely free: no ads, no analytics SDKs, no account. Everything runs on your iPhone without an internet connection, and no data is collected."),
    "ja-JP": ("ja", "棋眼 シャンチー・中国象棋", "対局・盤面撮影認識・棋譜検討",
              "シャンチー,中国象棋,象棋,ボードゲーム,詰め,オフライン,対局,戦略,棋譜,エンジン",
              "【オープンソースとプライバシー】アプリ全体がGPL-3で公開されており（github.com/zhangfeiyang/QiYan）、完全無料です。広告・解析SDK・アカウント登録は一切なし。すべての機能がiPhone上で動作し、通信は行わず、データも一切収集しません。"),
    "ko-KR": ("ko", "棋眼 샹치 · 중국장기", "대국 · 사진 인식 · 복기",
              "샹치,중국장기,중국 장기,보드게임,묘수풀이,오프라인,전략,기보,엔진",
              "【오픈소스와 프라이버시】앱 전체가 GPL-3로 공개되어 있으며(github.com/zhangfeiyang/QiYan) 완전히 무료입니다. 광고·분석 SDK·계정이 없습니다. 모든 기능이 iPhone에서 오프라인으로 동작하며 어떤 데이터도 수집하지 않습니다."),
    "fr-FR": ("fr-FR", "QiYan · Échecs chinois", "Jouer, scanner, analyser",
              "xiangqi,échecs chinois,jeu de société,stratégie,problèmes,hors ligne,moteur,entraînement",
              "【Open source et confidentialité】Toute l'application est open source sous GPL-3 (github.com/zhangfeiyang/QiYan) et entièrement gratuite : sans publicité, sans SDK d'analyse, sans compte. Tout fonctionne sur votre iPhone, sans connexion, et aucune donnée n'est collectée."),
    "de-DE": ("de-DE", "QiYan · Chinesisches Schach", "Spielen, scannen, analysieren",
              "Xiangqi,chinesisches Schach,Brettspiel,Strategie,Rätsel,offline,Engine,Training",
              "【Open Source & Datenschutz】Die gesamte App ist unter GPL-3 open source (github.com/zhangfeiyang/QiYan) und komplett kostenlos: keine Werbung, keine Analyse-SDKs, kein Konto. Alles läuft auf deinem iPhone ohne Internetverbindung, es werden keine Daten erhoben."),
    "es-ES": ("es-ES", "QiYan · Ajedrez chino", "Juega, escanea y analiza",
              "xiangqi,ajedrez chino,juego de mesa,estrategia,problemas,sin conexión,motor,entrenamiento",
              "【Código abierto y privacidad】Toda la app es open source bajo GPL-3 (github.com/zhangfeiyang/QiYan) y totalmente gratuita: sin anuncios, sin SDK de analítica y sin cuentas. Todo funciona en tu iPhone sin conexión y no se recopila ningún dato."),
    "pt-BR": ("pt-BR", "QiYan · Xadrez chinês", "Jogue, escaneie e analise",
              "xiangqi,xadrez chinês,jogo de tabuleiro,estratégia,problemas,offline,motor,treino",
              "【Código aberto e privacidade】O app inteiro é open source sob GPL-3 (github.com/zhangfeiyang/QiYan) e totalmente gratuito: sem anúncios, sem SDKs de análise e sem cadastro. Tudo funciona no seu iPhone sem conexão e nenhum dado é coletado."),
    "ru-RU": ("ru", "QiYan · Китайские шахматы", "Игра, фото доски, разбор",
              "сянци,китайские шахматы,настольная игра,стратегия,задачи,офлайн,движок,тренировка",
              "【Открытый код и приватность】Приложение полностью открыто под GPL-3 (github.com/zhangfeiyang/QiYan) и совершенно бесплатно: без рекламы, аналитических SDK и регистрации. Всё работает на iPhone без интернета, никакие данные не собираются."),
    "it-IT": ("it", "QiYan · Scacchi cinesi", "Gioca, scansiona, analizza",
              "xiangqi,scacchi cinesi,gioco da tavolo,strategia,problemi,offline,motore,allenamento",
              "【Open source e privacy】Tutta l'app è open source sotto GPL-3 (github.com/zhangfeiyang/QiYan) e completamente gratuita: niente pubblicità, SDK di analisi o account. Tutto funziona sul tuo iPhone senza connessione e nessun dato viene raccolto."),
    "th-TH": ("th", "QiYan หมากรุกจีน เซียงฉี", "เล่น สแกนกระดาน ทบทวนเกม",
              "เซียงฉี,หมากรุกจีน,เกมกระดาน,กลยุทธ์,ปริศนา,ออฟไลน์,ฝึกเล่น",
              "【โอเพนซอร์สและความเป็นส่วนตัว】แอปทั้งหมดเป็นโอเพนซอร์สภายใต้ GPL-3 (github.com/zhangfeiyang/QiYan) และฟรีทั้งหมด ไม่มีโฆษณา ไม่มี SDK เก็บสถิติ ไม่ต้องมีบัญชี ทุกฟีเจอร์ทำงานบน iPhone โดยไม่ต้องใช้อินเทอร์เน็ต และไม่เก็บข้อมูลใด ๆ"),
    "tr-TR": ("tr", "QiYan · Çin Satrancı", "Oyna, tahtayı tara, incele",
              "xiangqi,çin satrancı,masa oyunu,strateji,bulmaca,çevrimdışı,motor,antrenman",
              "【Açık kaynak ve gizlilik】Uygulamanın tamamı GPL-3 ile açık kaynaktır (github.com/zhangfeiyang/QiYan) ve tamamen ücretsizdir: reklam, analitik SDK veya hesap yok. Her şey iPhone'unuzda internet bağlantısı olmadan çalışır ve hiçbir veri toplanmaz."),
    "id-ID": ("id", "QiYan · Catur Tiongkok", "Main, pindai papan, ulas",
              "xiangqi,catur tiongkok,catur cina,permainan papan,strategi,teka-teki,offline,latihan",
              "【Open source & privasi】Seluruh aplikasi open source di bawah GPL-3 (github.com/zhangfeiyang/QiYan) dan sepenuhnya gratis: tanpa iklan, tanpa SDK analitik, tanpa akun. Semua fitur berjalan di iPhone Anda tanpa internet dan tidak ada data yang dikumpulkan."),
    "vi-VN": ("vi", "QiYan · Cờ Tướng", "Đấu cờ, quét bàn cờ, phục bàn",
              "cờ tướng,xiangqi,cờ trung quốc,trò chơi,chiến thuật,thế cờ,offline,luyện tập",
              "【Mã nguồn mở và quyền riêng tư】Toàn bộ ứng dụng mở theo giấy phép GPL-3 (github.com/zhangfeiyang/QiYan) và hoàn toàn miễn phí: không quảng cáo, không SDK thống kê, không cần tài khoản. Mọi tính năng chạy trên iPhone mà không cần Internet và không thu thập bất kỳ dữ liệu nào."),
    "ar-AE": ("ar-SA", "棋眼 · الشطرنج الصيني", "العب وامسح الرقعة وحلّل",
              "شيانغتشي,الشطرنج الصيني,لعبة لوحية,استراتيجية,ألغاز,دون اتصال,تدريب",
              "【مفتوح المصدر والخصوصية】التطبيق كله مفتوح المصدر برخصة GPL-3 (github.com/zhangfeiyang/QiYan) ومجاني بالكامل: بلا إعلانات ولا حزم تحليلات ولا حسابات. تعمل كل الميزات على iPhone دون اتصال بالإنترنت، ولا تُجمع أي بيانات."),
}

# "Android app" wording in the first line of each Play description
ANDROID = [
    ("中国象棋 Android 应用", "中国象棋应用"), ("中國象棋 Android 應用", "中國象棋應用"),
    ("app for Android", "app for iPhone"), ("Androidアプリ", "iPhoneアプリ"),
    ("application Android complète", "application complète"), ("App (Xiangqi) für Android", "App (Xiangqi) fürs iPhone"),
    ("app Android completa", "app completa"), ("app Android completo", "app completo"),
    ("полноценное Android-приложение", "полноценное приложение"), ("un'app Android completa", "un'app completa"),
    ("สำหรับ Android", "สำหรับ iPhone"), ("Android uygulaması", "uygulaması"),
    ("aplikasi Android catur", "aplikasi catur"), ("ứng dụng Android cờ tướng", "ứng dụng cờ tướng"),
]


# 胜率段: iOS 版没有安卓 AI 分析浮层里的评估分数/搜索深度, 只有胜率条
WINRATE = {
    "zh-Hans": "【实时胜率】 棋盘上方实时显示双方胜率，一眼看懂局面走向。",
    "zh-Hant": "【即時勝率】棋盤上方即時顯示雙方勝率，一眼看懂局面走向。",
    "en-US": "【Live win-rate】A real-time bar above the board shows both sides' winning chances.",
    "ja": "【勝率のライブ表示】盤面の上に両側の勝率をリアルタイム表示。形勢がひと目でわかります。",
    "ko": "【실시간 승률】판 위에 양측 승률을 실시간으로 표시합니다.",
    "fr-FR": "【Taux de victoire en direct】Une barre au-dessus du plateau affiche en temps réel les chances de chaque camp.",
    "de-DE": "【Siegchance in Echtzeit】Über dem Brett zeigt ein Balken live die Gewinnchancen beider Seiten.",
    "es-ES": "【Probabilidad de victoria en vivo】Una barra sobre el tablero muestra en tiempo real las opciones de ambos bandos.",
    "pt-BR": "【Chance de vitória ao vivo】Uma barra acima do tabuleiro mostra em tempo real as chances de cada lado.",
    "ru": "【Шансы на победу】Над доской в реальном времени показываются шансы обеих сторон.",
    "it": "【Probabilità di vittoria in tempo reale】Una barra sopra la scacchiera mostra in tempo reale le possibilità di entrambi i lati.",
    "th": "【โอกาสชนะแบบสด】แถบเหนือกระดานแสดงโอกาสชนะของทั้งสองฝ่ายแบบเรียลไทม์",
    "tr": "【Canlı kazanma şansı】Tahtanın üzerindeki çubuk her iki tarafın kazanma şansını gerçek zamanlı gösterir.",
    "id": "【Peluang menang langsung】Bilah di atas papan menampilkan peluang menang kedua sisi secara real-time.",
    "vi": "【Tỷ lệ thắng trực tiếp】Thanh phía trên bàn cờ hiển thị thời gian thực tỷ lệ thắng của hai bên.",
    "ar-SA": "【نسبة الفوز لحظيًا】شريط فوق الرقعة يعرض لحظيًا فرص الفوز للطرفين.",
}


def main():
    out = {}
    for play_loc, (loc, name, subtitle, keywords, privacy) in L.items():
        src = PLAY[play_loc]["full"]
        lines = src.split("\n")
        # 开源与隐私段 (含 github 链接的那一行) 换成 iOS 版的说法
        lines = [privacy if "github.com/zhangfeiyang/QiYan" in l else l for l in lines]
        headed = [i for i, l in enumerate(lines) if l.startswith("【")]
        lines[headed[5]] = WINRATE[loc]
        desc = "\n".join(lines)
        for a, b in ANDROID:
            desc = desc.replace(a, b)
        desc = desc.replace("نقسة", "نقلة")   # 安卓阿语文案的错字
        assert "Android" not in desc, (loc, re.findall(r".{20}Android.{20}", desc))
        for field, limit, value in (("name", 30, name), ("subtitle", 30, subtitle), ("keywords", 100, keywords),
                                    ("desc", 4000, desc)):
            assert len(value) <= limit, (loc, field, len(value))
        out[loc] = {"name": name, "subtitle": subtitle, "keywords": keywords, "desc": desc, "promo": ""}
    path = os.path.join(HERE, "metadata.json")
    json.dump(out, open(path, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    print(f"wrote {path}: {len(out)} locales")


if __name__ == "__main__":
    main()
