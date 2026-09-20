# ARCore_fetcher

ARCore 端末で, 多視点画像とカメラ姿勢を撮影し, データの書き出しを行う Android アプリ.

手動シャッターで 1 枚ずつ撮り、各フレームの画像・カメラ姿勢・内部パラメータを
`transforms.json` にまとめて書き出す。データは ZIP にして共有シートから取り出す。

ビルドから撮影までの手順は [docs/setup_guide.html](docs/setup_guide.html) にある
（Android Studio を初めて使う人向け）。

---

## 出力

1 撮影セッション = 1 ディレクトリ = 1 `transforms.json`。

```
capture_20260920T103104/
├── transforms.json
└── images/
    ├── 000000812345678901.jpg
    └── ...
```

画像ファイル名は `timestamp_ns` の 18 桁ゼロ埋め。連番はフレーム欠損で破綻するので使わない。

書き出し先は `getExternalFilesDir(null)/captures/` 配下で、
同じ場所に ZIP も作られる。ZIP は書き出し完了と同時に Android の共有シートへ渡る
（Drive・AirDrop・メールなど、端末に入っている転送手段が使える）。

### transforms.json

```json
{
  "spec_version": "arcore-fetcher/capture/1.0",

  "camera_model": "PINHOLE",
  "fl_x": 505.12, "fl_y": 505.12,
  "cx": 320.0, "cy": 240.0,
  "w": 640, "h": 480,

  "coordinate_convention": {
    "handedness": "right",
    "camera_axes": "OpenGL (+X right, +Y up, -Z forward)",
    "matrix_layout": "row-major",
    "quaternion_order": "xyzw",
    "transform_direction": "camera-to-world",
    "world_up": [0.0, 1.0, 0.0],
    "length_unit": "meter"
  },

  "capture": {
    "session_id": "20260920T103104+0900",
    "device_model": "Pixel 8 Pro",
    "arcore_version": "1.47.0",
    "capture_mode": "cpu_image",
    "world_origin": "session",
    "origin_refreshed_at_end": true
  },

  "frames": [
    {
      "file_path": "images/000000812345678901.jpg",
      "timestamp_ns": 812345678901,
      "transform_matrix": [[1.0, 0.0, 0.0, 0.12],
                           [0.0, 1.0, 0.0, 1.45],
                           [0.0, 0.0, 1.0, -0.33],
                           [0.0, 0.0, 0.0, 1.0]],
      "translation": [0.12, 1.45, -0.33],
      "quaternion_xyzw": [0.0, 0.0, 0.0, 1.0],
      "tracking_state": "TRACKING",
      "tracking_failure_reason": "NONE"
    }
  ]
}
```

`coordinate_convention` は読み手が想定外の規約のデータを弾くための宣言なので、値は固定。
intrinsics はセッション中不変なのでトップレベルに置き、
フレームごとに変わる場合に限りフレーム側で 6 キーまとめて上書きする。

`transform_matrix` と `translation` + `quaternion_xyzw` は同一の姿勢を冗長に表現したもので、
両方書き出す。前者は下流ツールとの互換のため、後者は ARCore からの可逆な生データとして。

スケールは実寸（メートル）。ARCore の出力にはスケール不定性がない。

### 記録するが選別しない

`TRACKING` が外れた（`PAUSED`）状態で撮ったフレームも**捨てずに記録する**。
低品質フレームの基準は撮影後に変えられるべきものなので、撮影側では判断しない。
`tracking_state` と `tracking_failure_reason` を各フレームに残してあるので、
選別は書き出したデータを読む側で行う。

## 使い方

1. アプリを起動してカメラ権限を許可する
2. 端末をゆっくり動かし、画面上部の表示が `TRACKING` になるのを待つ
   （この時点でセッションの root Anchor が張られる）
3. **撮影** ボタンで 1 枚ずつ撮る。対象のまわりを回り込みながら、
   視点を変えて 30〜100 枚程度
4. **書き出し** ボタンで `transforms.json` と ZIP を作り、共有シートで転送する

## 設計

### 座標とドリフト補正が肝

ARCore の VIO はループクローズ・再ローカライズで**過去の world 座標系を後から書き換える**。
撮影時点で記録した world 絶対姿勢はセッション終了時には陳腐化している可能性があるので、
確定させてはいけない。本アプリは次の 2 つを両方行う。

1. セッション冒頭に root Anchor を 1 つ張り、各フレームを
   `anchor.pose.inverse().compose(camera.pose)`（**anchor 相対**）で保持する
2. セッション終了直前に root Anchor の `pose` を読み直し、それを全フレームに掛け戻す

root Anchor は回転を単位クォータニオンにして生成する。これにより ARCore world の
重力アライン（`+Y` = 上）が anchor 座標系に引き継がれる。
掛け戻した最終出力は ARCore world 座標系そのものなので、
`capture.world_origin` は `"session"`、`origin_refreshed_at_end` は `true`。

トラッキングが一度も確立せず Anchor を張れないまま終わったセッションでは、
補正をしていないので `origin_refreshed_at_end` に正直に `false` が入る。

### スレッド

ARCore のオブジェクト（`Session` / `Frame` / `Camera` / `Anchor`）は
**すべて GL スレッド（`onDrawFrame`）からのみ触る**。
UI スレッドからは `AtomicBoolean` のリクエストフラグ越しに依頼する。

JPEG エンコードとディスク書き込みは専用ワーカースレッドへ逃がし、描画ループを止めない。
`Image` はプロセス共有バッファなので、GL スレッドで NV21 にコピーしてから即 `close()` する。

### 取り違えやすい点と実装箇所

| 項目 | 実装箇所 |
|---|---|
| `camera.getPose()` を使う（`getDisplayOrientedPose()` は描画用で intrinsics と軸が合わない） | `MainActivity.captureFrame()` |
| `Pose.toMatrix()` は列優先。JSON へは行優先に転置する | `PoseMath.toRowMajorMatrix()` |
| quaternion は `(x,y,z,w)` 順のまま出力（`wxyz` へ並べ替えない） | `PoseMath.quaternionXyzw()` |
| intrinsics は実際に保存した画像の解像度に対応（リサイズ時は同率スケール） | `MainActivity.intrinsicsFor()` |
| pose は anchor 相対、終了直前に再取得 | 上記「座標とドリフト補正」 |
| `TRACKING` 以外のフレームも捨てずに記録 | `captureFrame()` は trackingState で弾かない |
| ファイル名は `timestamp_ns` の 18 桁ゼロ埋め | `CaptureSessionWriter.writeFrame()` |
| EXIF は書かない — `transforms.json` が single source of truth | `YuvJpeg` |

画像は**回転させずに**保存する。`getImageIntrinsics()` は未回転のセンサ座標で
報告されるため、回転すると intrinsics と画像が食い違う。

### ファイル構成

```
app/src/main/java/com/example/arcorefetcher/
├── MainActivity.kt              # ARCore セッション管理・シャッター処理
├── capture/
│   ├── CaptureModel.kt          # CaptureSpec / Intrinsics / CaptureMeta / PendingFrame
│   ├── PoseMath.kt              # 転置・quaternion・translation の変換
│   ├── TransformsJson.kt        # transforms.json シリアライザ
│   ├── CaptureSessionWriter.kt  # ディレクトリ書き出し・ZIP 化
│   ├── YuvJpeg.kt               # YUV_420_888 → NV21 → JPEG
│   └── Zip.kt
└── render/BackgroundRenderer.kt # カメラ映像を描く最小 GL レンダラ
```

## 技術構成

- Kotlin 2.0.21 / AGP 8.7.3 / Gradle 8.9
- compileSdk 35 / minSdk 24 / targetSdk 35 / JVM 17
- `com.google.ar:core:1.47.0`、androidx core-ktx / appcompat / material

## パッケージ名について

現在の `com.example.arcorefetcher` は**どの組織も名乗らない暫定プレースホルダ**であり、
恒久的な名前は未確定。

これは**個人開発**のプロジェクトなので、
慣習的には `io.github.<GitHub ユーザー名>.arcorefetcher` のような名前が妥当。
組織名を冠する判断は技術的な既定値ではないため、勝手に変更しないこと。

変更するときは以下をすべて揃える。

- `app/build.gradle.kts` の `namespace` / `applicationId`
- 各 Kotlin ファイルの `package` / `import` とディレクトリ階層
- 本 `README.md` と `docs/setup_guide.html` の記述

`AndroidManifest.xml` の FileProvider authority は `${applicationId}.fileprovider` と
書いてあるので追従は不要。

## 状態

- **未コンパイル。** Android Studio での初回ビルドがまだ通っていない
- **実機未検証。** 1 セッション撮って出力を確認するのが次の作業
- Gradle Wrapper の JAR (`gradle/wrapper/gradle-wrapper.jar`) を含めていない。
  Android Studio が自動生成するが、失敗したら `gradle wrapper --gradle-version 8.9` で用意する

### 実機で最初に出そうな問題

- **CPU 画像解像度が端末依存で低い。** `getSupportedCameraConfigs()` から最大を選んでいるが、
  640x480 止まりの端末がある
- `getImageIntrinsics()` の報告サイズと実際の取得画像サイズのずれ。
  同率スケール補正は入れてあるが、ずれていても出力は一見もっともらしくなるので、
  最初の実機セッションで `fl_x` / `cx` / `w` が実際の画像と合っているか目視確認すること
- `tracking=PAUSED` から復帰しない（明るさ・特徴点不足）

### 未対応

| 項目 | 内容 |
|---|---|
| depth | `Config.DepthMode` を有効化し `Frame.acquireDepthImage16Bits()` を 16bit grayscale PNG（mm）で保存。オプショナル項目なので後方互換に追加できる |
| `shared_camera` | 高解像度静止画。静止画とプレビューで解像度が変わるため、フレーム単位の intrinsics 上書きが必要。`PendingFrame.intrinsicsOverride` として配線済み |
| `exposure_ns` / `iso` | `acquireCameraImage()` の `Image` には撮影メタデータが付かない。取るなら Shared Camera 経由で `CaptureResult` を読む必要がある。オプショナル |
| `distortion` | ARCore は歪み係数を返さない。入れるなら別途チェッカーボード校正が必要 |
