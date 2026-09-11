package com.duiyi.android.domain

enum class DuiyiError {
    MicrophoneDenied,
    ModelsMissing,
    SpeechModelsMissing,
    LanguageUnsupported,
    RecognitionInterrupted,
    SpeechEngineSilent,
    GoogleMicrophoneDenied,
    HonorRecognizerBlocked,
    TranslationUnavailable,
    DeviceIncapable,
    SummaryEmpty,
    VoiceOutNeedsHeadphones,
    SynthesisVoiceMissing,
    PlayServicesMissing,
    ;

    fun userMessage(language: AppLanguage): String = when (this) {
        MicrophoneDenied -> language.pick(
            zh = "请在设置中允许麦克风。",
            en = "Allow the microphone in Settings.",
            ja = "設定でマイクを許可してください。",
            ko = "설정에서 마이크를 허용해 주세요.",
            th = "อนุญาตไมโครโฟนในการตั้งค่า",
            vi = "Hãy cho phép micro trong Cài đặt.",
        )
        ModelsMissing -> language.pick(
            zh = "需要先下载离线翻译模型 TranslateGemma 4B，约 2.4 GB，之后可完全离线。",
            en = "Download the on-device TranslateGemma 4B model first. About 2.4 GB, then translation stays on this phone.",
            ja = "先に TranslateGemma 4B（約 2.4 GB）をダウンロードしてください。",
            ko = "먼저 TranslateGemma 4B(약 2.4GB)를 다운로드하세요.",
            th = "ต้องดาวน์โหลด TranslateGemma 4B ก่อน ประมาณ 2.4 GB",
            vi = "Hãy tải TranslateGemma 4B trước. Khoảng 2.4 GB.",
        )
        SpeechModelsMissing -> language.pick(
            zh = "需要先下载离线听写模型 SenseVoice，约 240 MB。之后对着手机说话即可出字幕。",
            en = "Download the on-device SenseVoice speech model first. About 240 MB, then speak to the phone for captions.",
            ja = "先に SenseVoice（約 240 MB）をダウンロードしてください。",
            ko = "먼저 SenseVoice(약 240MB)를 다운로드하세요.",
            th = "ต้องดาวน์โหลด SenseVoice ก่อน ประมาณ 240 MB",
            vi = "Hãy tải SenseVoice trước. Khoảng 240 MB.",
        )
        LanguageUnsupported, DeviceIncapable -> language.pick(
            zh = "这台设备暂时不能离线识别这对语言。",
            en = "This device cannot transcribe this language pair offline yet.",
            ja = "このデバイスでは、この言語の組み合わせをオフライン認識できません。",
            ko = "이 기기는 아직 이 언어 쌍을 오프라인으로 인식할 수 없습니다.",
            th = "อุปกรณ์นี้ยังถอดเสียงคู่ภาษานี้แบบออฟไลน์ไม่ได้",
            vi = "Thiết bị này chưa nhận dạng được cặp ngôn ngữ này khi ngoại tuyến.",
        )
        RecognitionInterrupted -> language.pick(
            zh = "识别暂停了，轻点继续。",
            en = "Listening paused. Tap to continue.",
            ja = "聞き取りが止まりました。タップして続けます。",
            ko = "듣기가 멈췄습니다. 탭하여 계속하세요.",
            th = "การฟังหยุดแล้ว แตะเพื่อต่อ",
            vi = "Đã tạm dừng nghe. Chạm để tiếp tục.",
        )
        SpeechEngineSilent -> language.pick(
            zh = "打不开 Google 听写界面。请先打开一次「Google」应用并用里面的语音输入说一句话，再回到对译点开始。",
            en = "Google dictation did not open. Open the Google app, use voice input once, then return to Duiyi and tap Start.",
            ja = "Google の音声入力が開きません。先に Google アプリで音声入力を一度使ってから、Duiyi で開始してください。",
            ko = "Google 받아쓰기가 열리지 않습니다. Google 앱에서 음성 입력을 한 번 쓴 뒤 다시 시작하세요.",
            th = "เปิดหน้าต่าง Google ไม่ได้ เปิดแอป Google ใช้เสียงครั้งหนึ่งแล้วกลับมาเริ่ม",
            vi = "Không mở được nghe Google. Hãy mở ứng dụng Google, dùng nhập giọng một lần, rồi bắt đầu lại.",
        )
        GoogleMicrophoneDenied -> language.pick(
            zh = "听写跑在 Google 应用里，只开对译的麦克风不够。请给「Google」打开麦克风后重试。轻点这行可打开 Google 的应用详情。",
            en = "Captions run inside the Google app. Duiyi’s microphone permission is not enough. Allow Microphone for Google, then try again. Tap this line to open Google’s app info.",
            ja = "音声認識は Google アプリ側です。Duiyi のマイクだけでは足りません。Google のマイクを許可してください。",
            ko = "받아쓰기는 Google 앱에서 돌아갑니다. Duiyi 마이크만으로는 부족합니다. Google에 마이크를 허용하세요.",
            th = "การถอดเสียงรันในแอป Google ไมค์ของ Duiyi ไม่พอ ต้องอนุญาตไมค์ให้ Google",
            vi = "Nhận giọng chạy trong ứng dụng Google. Chỉ cấp micro cho Duiyi là chưa đủ. Hãy cấp micro cho Google.",
        )
        HonorRecognizerBlocked -> language.pick(
            zh = "荣耀默认语音识别是「魔法语音」，不给第三方字幕。请到设置把默认语音输入改成 Google，再回到对译点开始。轻点这行可打开语音输入设置。",
            en = "Honor’s default recognizer is Magic Voice, which does not caption third-party apps. Set the default voice input to Google, then tap Start again. Tap this line to open Voice input settings.",
            ja = "Honor のデフォルト認識は Magic Voice で、他社アプリには字幕を出しません。デフォルトの音声入力を Google に変えてください。",
            ko = "Honor 기본 인식은 Magic Voice라 타사 앱에 자막을 주지 않습니다. 기본 음성 입력을 Google로 바꾸세요.",
            th = "ตัวรู้จำเริ่มต้นของ Honor คือ Magic Voice ไม่ให้คำบรรยายแอปอื่น ตั้งค่าอินพุตเสียงเป็น Google",
            vi = "Nhận dạng mặc định Honor là Magic Voice, không phụ đề cho ứng dụng khác. Hãy đặt nhập giọng thành Google.",
        )
        TranslationUnavailable -> language.pick(
            zh = "听清了，但译文还没好。",
            en = "Heard you, but the translation is not ready.",
            ja = "聞こえましたが、訳がまだありません。",
            ko = "듣기는 했지만 번역이 아직 없습니다.",
            th = "ได้ยินแล้ว แต่คำแปลยังไม่พร้อม",
            vi = "Đã nghe, nhưng bản dịch chưa sẵn sàng.",
        )
        SummaryEmpty -> language.pick(
            zh = "还没有可总结的对话。",
            en = "There is no conversation to summarize yet.",
            ja = "要約できる会話がまだありません。",
            ko = "요약할 대화가 아직 없습니다.",
            th = "ยังไม่มีบทสนทนาให้สรุป",
            vi = "Chưa có cuộc trò chuyện để tóm tắt.",
        )
        VoiceOutNeedsHeadphones -> language.pick(
            zh = "请戴上耳机再读出译文。",
            en = "Wear headphones to hear the translation.",
            ja = "訳を読み上げるにはイヤホンをつけてください。",
            ko = "번역을 들으려면 이어폰을 착용하세요.",
            th = "ใส่หูฟังก่อนให้อ่านคำแปล",
            vi = "Hãy đeo tai nghe để nghe bản dịch.",
        )
        SynthesisVoiceMissing -> language.pick(
            zh = "这台设备没有该语言的朗读声。",
            en = "This phone has no voice for that language.",
            ja = "このデバイスには、その言語の読み上げ音声がありません。",
            ko = "이 기기에는 해당 언어의 음성이 없습니다.",
            th = "อุปกรณ์นี้ไม่มีเสียงอ่านภาษานั้น",
            vi = "Máy này không có giọng đọc cho ngôn ngữ đó.",
        )
        PlayServicesMissing -> language.pick(
            zh = "这台手机没有 Google 服务。非荣耀机型的听写可能需要 Google 应用。翻译已改为本机 TranslateGemma，不依赖 GMS。",
            en = "Google Play services are missing. Non-Honor dictation may need the Google app. Translation now uses on-device TranslateGemma and does not need GMS.",
            ja = "Google サービスがありません。翻訳は端末内の TranslateGemma を使います。",
            ko = "Google 서비스가 없습니다. 번역은 기기의 TranslateGemma를 씁니다.",
            th = "ไม่มีบริการ Google การแปลใช้ TranslateGemma บนเครื่อง",
            vi = "Thiếu Google Play. Bản dịch dùng TranslateGemma trên máy, không cần GMS.",
        )
    }
}
