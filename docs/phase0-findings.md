# Phase 0 調査結果

調査日: 2026-10-13。対象は開発機に入っている E-Sys 3.30.1(PSdZ 5.04.02)。
E-Sys は起動しておらず、車両にも接続していない。ここに書いた内容は、インストール済みファイルの確認と、デコンパイルしたコードの静的な読み取りによるもの。実機での確認が必要な項目は「要実機確認」と書いた。

デコンパイル結果そのものはリポジトリに含めない(公開制約)。ここではクラス名とメソッド名だけを参照する。

## 1. E-Sys 同梱 JRE

| 項目 | 値 |
|---|---|
| E-Sys | 3.30.1(`C:\EC-Apps\ESG\E-Sys`) |
| PSdZ | 5.04.02 |
| 同梱 JRE | 1.8.0_92、32bit HotSpot Client VM |
| E-Sys jar のビルド JDK | 1.8.0_92 |

agent は Java 8(class file 52)でコンパイルする必要がある。

- JDK 26 の `javac --release 8` で作った jar が、同梱 JRE で `-javaagent` として読み込まれ premain が動くことを確認済み(E-Sys 本体は起動していない)。
- JDK 26 は `--release 8` に「obsolete、将来削除予定」の警告を出す。削除時期を決めた JEP は見つからなかった。当面は `-Xlint:-options` で警告を抑え、削除されたら Maven toolchains で JDK 17/21 を agent だけに使う。

## 2. 起動方法と -javaagent の差し込み位置

- `E-Sys.exe` は `E-Sys.bat` を起動するだけのランチャー。
- `E-Sys.bat` は `-cp` を明示して `javaw ... com.bmw.esys.gui.control.Framework` を起動する。
- 引数付きで起動すると batch モード(`com.bmw.esys.domain.batch.EsysBatch`)になる。

差し込み位置は `E-Sys.bat` の GUI 起動行。元の bat は書き換えず、`-javaagent` を足したコピー(例: `E-Sys-agent.bat`)を利用者が作る方針にする。

```bat
start javaw -javaagent:C:\path\to\esys-agent.jar %OPTION_ENDORSED% %OPTION_MEMORY% %OPTION_THREAD% %OPTION_JMX% %OPTION_GC% -splash:../splash.gif com.bmw.esys.gui.control.Framework
```

注意点:

- bat は `lib\environment` に `cd` してから起動するので、agent の jar パスは絶対パスで書く。
- 既存の起動オプションに `-Dcom.sun.management.jmxremote` が入っている(ローカル JMX が有効)。

## 3. 設定とデータの場所

| 項目 | 値 |
|---|---|
| 設定 | `config\Esys.properties` と `%USERPROFILE%\Esys\EsysUser.properties` |
| データディレクトリ | `Path.Home`。存在しないと E-Sys が `C:\Temp\Data_<日時>` を新しく作る |
| PSdZData | 本機では未展開。`Esys.properties` は存在しない `L:\bmw-pszdata` を指している |
| EST トークン | `Path.EST`(`C:\data\` 配下)。リポジトリに入れない、触らない |
| 既定の接続 | `CurrentConnection=1`(gateway_url)、`GatewayUrl=tcp://127.0.0.1:6801` |

PSdZData の 7z(Lite 4.54.20、Full 4.58〜4.60 など)は M: にある。Lite から CAFD だけを一時領域に展開して形式を確認した(8,675 ファイル)。

## 4. E-Sys の batch / client-server モード

E-Sys には公式ヘルプに載っている headless モードがある。読み取り系は javaagent なしでもこれで実現できる可能性がある。

- `E-Sys.bat -startserver` で batch サーバーが `localhost:30303` で待ち受ける(`BatchServer.port` で変更可)。
- クライアントは 1 行を送る。引数は `$$$` で区切る。サーバーは出力を行単位で返し、最後に `###<終了コード>` を送る。
- ヘルプによると、GUI と batch サーバーは同じ PC で同時に動かせない(1 台に 1 インスタンス)。

接続設定ファイル(IP 指定の例)。キー名は `ConnectionPropertiesReader` で確認済み。

```properties
PROJECT=F020_xx_xx_xxx
VEHICLEINFO=F020
CONNECTION=gateway_url
URL=tcp://192.168.x.x:6801
READ_VEHICLE_CONNECTION_PARAMETER=on
```

`CONNECTION` の値は `bus`、`gateway_url`、`icom_d_can`、`icom_ethernet`、`vin` の 5 種類。`vin` のときは `GATEWAY_VIN=<VIN>_DIAGADR<hex>` か `AUTODETECT` を使う。

読み取りの流れ:

```text
E-Sys.bat -startserver
E-Sys.bat -server -openconnection <接続設定>
E-Sys.bat -server -readfa  -out <dir>
E-Sys.bat -server -readsvt -out <dir>
E-Sys.bat -server -readncd <SVT ファイル> -out <dir>
E-Sys.bat -server -closeconnection
E-Sys.bat -server -stop
```

`-readncd` は SVT にある CAFD ごとに `<VIN 下 7 桁>_<SGBMID>_<基本バリアント>_<yyyyMMddHHmm>.fwl` を書き出す。ヘルプには拡張子 `.xml` と書かれているが、コードは `.fwl`。要実機確認。

## 5. FWL 形式(Phase 1 の入力候補)

`-readncd` と Coding Verification が出力する FWL はプレーンテキスト。コードから読み取れる行の形は次のとおり(要実機確認)。

```text
CAF_ID: CAFD_0000xxxx_vvv_vvv_vvv          (coding2 の 1 行目)
<機能名>: <値の名前> [<hex> <hex> ...]       (値テーブルに一致した場合)
<機能名>: <hex> <hex> ...                   (計算値の場合)
```

- coding1 は `Fwl`、coding2 は `Fwl2` が生成する。
- 生成の流れは `CodingLogic.readNCD(ecu, partIdent)` から `CodingUtils.saveFwl(ncd, sgbmid, file)` まで。GUI の中の agent も同じ 2 つの呼び出しで FWL を作れる。

CAFD ファイル自体は暗号化されたコンテナで、平文ではない。esys-mcp はこれを自前で読まない。パラメータ名と値は E-Sys 自身が出力する FWL(と FDL エディタの表示)から取る。これで「E-Sys の既存 API を呼ぶだけ」という制約に収まり、Phase 1 は FWL パーサーになる。

## 6. MCP Java SDK

| 項目 | 値 |
|---|---|
| 最新安定版 | 2.0.1(2026-08-19) |
| 要求 Java | 17 以上 |
| Maven | `io.modelcontextprotocol.sdk:mcp`(= `mcp-core` + `mcp-json-jackson3`)、BOM は `mcp-bom` |
| stdio | `mcp-core` に含まれる |
| 準拠テストの仕様版 | 2025-11-25 |

親 pom の `dependencyManagement` に `mcp-bom` 2.0.1 を入れた。server モジュールへの依存追加は Phase 3 で行う。

## 7. ビルド構成(決定)

- Gradle は本機に入っていないので Maven マルチモジュールにした(メモで Maven も可としていた)。
- `core` と `server` は release 17、`agent` は release 8。
- E-Sys の jar は agent の `system` スコープで参照するだけで、配布物には含めない。パスは `-Desys.home=...` で変えられる。
- agent jar のマニフェストには `Premain-Class` を入れ、`Can-Redefine-Classes` と `Can-Retransform-Classes` は false にした。

## 8. GUI の読み取り経路

サブ調査の報告をもとにまとめた。1 と 2 の要点(接続の置き換え、SVT 読み取りの副作用)はコードを見て確認済み。それ以外は報告ベースで、実機確認が必要。

### 接続

- 接続ダイアログ `ConnectionDialog` は TargetSelector の一覧から `PROJECT` と `VEHICLEINFO` を取り、`ConnectionProperties` を組み立てて `ConnectionLogic.openConnection` を呼ぶ。
- IP 指定の場合は、接続種別 `gateway_url`、URL `tcp://<IP>:6801` の形になる。VIN 自動検出も最終的には Ethernet 接続になる。
- PROJECT と VEHICLEINFO は車両からは決まらない。利用者が一覧から選ぶ。agent には一覧を返す操作(listTargets)と、それを指定して接続する操作が必要。
- シリーズと I-Step は、手入力するか、VCM から読む(いったん接続して読み、つなぎ直す)。
- **PSdZ の接続は JVM 全体で 1 本だけ。新しく開くと、既存の接続を確認なしで閉じる**(`Psdz.doOpenConnection`、確認済み)。agent は接続済みなら connect を拒否する。
- agent が接続しても、GUI のステータス表示は更新されない。
- 本機は `HttpUpdate.Activation=true` なので、gateway_url で接続すると PSdZ が HTTP サーバーを起動し、待ち受けポートが開く。

### FA、SVT、NCD の読み取り

| 対象 | 呼ぶもの | 注意 |
|---|---|---|
| FA | `new FalLogic().readFa()`、保存は `save(File)` | 失敗するとバックアップ側から読む。`isFromBackup()` で判別できる |
| SVT | `new SvtLogic().readSvtActualEcu()`、文字列化は `marshal()` | batch の `-readsvt` も同じ経路 |
| NCD | `new CodingLogic(null, null).readNCD(ecuId, partIdent)` | 読み取りだけならコンストラクタ引数は不要 |

- **GUI の SVT 読み取り(VCM から)は、読む前に VCM の SVT 再生成を実行することがある**(`readSvtActual` が `generateSvtActual` を呼ぶ、確認済み)。読み取り専用の段階では使わず、ECU から読む `readSvtActualEcu` を使う。
- NCD の入力は SVT から作る。ECU ごとに診断アドレスと基本バリアントで `EcuIdentifier` を作り、SVK の中で CAFD に当たる部品番号を指定する。
- GUI は読んだ NCD を `Path.Home\CAF\<部品番号>.ncd` に自動保存する。agent は自分専用のフォルダに書き、GUI のファイルを上書きしない。

### トークン(EST)の扱い

- 接続、FA、SVT、NCD の読み取りでは、GUI も batch もトークンを開かない。
- トークンを開くのは次の 2 か所。
  - FDL エディタ、Coding Verification、CAF Viewer のモジュールを開くとき
  - FDL を使ってコーディングする(書き込む)とき
- FWL を出力する処理は、GUI では Coding Verification(トークンが必要なモジュール)の機能。batch の `-readncd` は同じ処理をトークンなしで呼んでいる。agent で FWL を出すときに GUI と同じくトークンを前提にするかは、決める必要がある。

## 9. 起動順序、スレッド、排他

### 起動順序と agent の開始時点

E-Sys は `main` スレッドで次の順に初期化する。設定の読み込み、Java バージョン確認、データディレクトリ、ログ開始(ここで PSdZ も初期化される)、フレーム表示、起動時ダイアログ。

- 設定の読み込みはスレッドセーフでない。agent が初期化途中に E-Sys のクラスに触ると壊れる可能性がある。
- **premain では E-Sys のクラスに一切触らず、デーモンスレッドを立てて待つだけにする。**
- 開始の合図は JDK の API だけで判定する。`Frame.getFrames()` に表示中の `EsysFrame` があり、`main` スレッドが終わっていれば開始してよい。

### スレッドと排他

- 接続、FA 読み取り、SVT 読み取りは、GUI では EDT 上で同期的に動く。その間カーソルが待機表示になる。
- NCD 読み取りとコーディングは、モーダルな進捗ダイアログを出して別スレッドで動く。TAL 実行も専用スレッド。
- **GUI 全体の「処理中」フラグやロックは存在しない。**
- PSdZ の内部ロックはアトミックでない。しかも、ロックの取得に失敗した側が finally で解放してしまい、実行中の TAL が持っているロックを外せる作りになっている。
- したがって agent は次のようにする。
  - PSdZ の呼び出しは 1 本のスレッドに直列化する。
  - GUI 側で何か動いていそうなら拒否する。目安は、待機カーソル、進捗ダイアログやモーダルダイアログの表示、EDT が 500ms 以内に応答しない、TAL / HDD モジュールが開いている、など。
  - どれもヒューリスティックなので、完全な排他にはならない。運用で「agent 使用中は人間が GUI を操作しない」ことを前提にする。

### 障害の隔離

- E-Sys には既定の UncaughtExceptionHandler がない。agent のスレッドで例外が起きても、そのスレッドが終わるだけで E-Sys は落ちない。スタックトレースは E-Sys のログに出る。
- E-Sys が終了するのは、終了・再起動の API を呼んだときと、起動時のエラーのとき。agent はダイアログ表示、モジュールを開く処理、再起動の API を呼ばない。
- ハンドラーはすべて Throwable を捕まえる。

### バージョン(書き込み allowlist 用)

- `Version.getNumber()` は "3.30.1"、`Version.getBuildNumber()` は "49938"。
- PSdZ のバージョンは `Version.getVersionPsdz()` で取れる。失敗すると "error" という文字列を返すので、allowlist はそれを弾く。

### agent の骨組み(案)

```text
premain: デーモンスレッドを起動するだけ。E-Sys のクラスには触らない。
起動待ち: 500ms ごとに、EsysFrame が表示中で main スレッドが終わったかを確認する。
開始: バージョンを読み、allowlist に無ければ状態取得系だけを公開する。
      127.0.0.1 で HTTP サーバーを立て、処理は 1 本のスレッドで直列に実行する。
getStatus: 接続中か、接続先、開いているモジュール、GUI がビジーかどうか
listTargets: TargetSelector の一覧
connect(ip, project, vehicleInfo): ビジーなら拒否、接続済みなら拒否、ConnectionLogic で接続
readFA / readSVT(ECU から) / readNCD(ecu): 結果は agent 専用フォルダに保存して返す
```

## 10. FA を変えて NCD を計算する経路(オフライン)

E-Sys 同梱のヘルプ(`help\E-SysEn.chm`)で確認した。デコンパイルしたコードではなく、公式のドキュメント。

- FWL は **Funktionswertliste**(機能値リスト)の略(ヘルプの略語一覧)。NCD は Nettocodierdaten、FP は Fahrzeugprofil。
- Coding-Verification モジュールは、FA、CAF、SVT が整合するかを**オフラインで**検査する。トレースとして NCD、FWL、SVT、FP をそれぞれ出力できる。
- batch には同じ処理のコマンドがある。車両には接続しない。

```text
E-Sys.bat -generatencd <FA または FA リストのファイル> -trace <出力先> [-caf <CAF 名>[,<CAF 名>...]]
例: E-Sys.bat -generatencd C:\Data\FA\F020.xml -trace C:\Data\Trace -caf cafd_0000815.caf.001_001_003
```

- 入力は FA でも FA リストでもよい。FA リストなら、中の全 FA について NCD を計算する。FA を何通りも試すときは、1 つの FA リストにまとめて 1 回で計算できる。
- `-caf` を省くと、取り込み済みの全 CAF について計算する。
- 出力先に同名のファイルがあると、警告なしで上書きする。ジョブごとに出力先を分ける。
- 戻り値は 0(全部成功)と 1(一部失敗)。
- ヘルプには「NCD と FP のトレースを出力する」とだけある。batch で FWL も出るかは要実機確認。出なければ GUI の Coding-Verification の設定(FWL トレース)を使うか、別の手段を考える。
- 計算には CAF(PSdZData の CAFD)が要る。本機は PSdZData が未展開なので、まず Lite を展開して `Esys.properties` から参照させる必要がある。
- `-readncd` の出力ファイル名は、ヘルプでは `<VIN 下 7 桁>_<CAF>_<基本バリアント>_<ddmmyyyyhhmm>.xml`。中身は FWL 形式とある。§4 のコードからの読み取り(`.fwl`、`yyyyMMddHHmm`)と食い違うので、実機で確かめる。
- FA リストの XML スキーマはヘルプに同梱されていない。形式は、F31 から読んだ実物の FA で確かめる。

この経路なら、車両への書き込みも署名も通らない。esys-mcp の主な用途(FA を変えて NCD を計算し、FWL を比べる)はここに載せる。

## 11. 未調査の項目

次の項目は調査を途中で止めたため、結論が出ていない。デコンパイルしたコードの深い読み込みは避け、公開されている batch モードや実機での観察で確かめる方針にする。

- **書き込み経路**: 「Code」「Code FDL」がどの TAL を作り、どこを通るか。prepare/commit のゲートをどこに置くか。
- **署名**: FDL の署名は `SecurityHandler.sign` を経由する(`FdlLogic` で確認)。それ以上は調べていない。
- **電圧**: PSdZ の前提条件コードに `UBATT` があることだけ確認。取得方法は未定。
- **NCD 読み取りの PSdZ 内部**: PSdZ の該当部分はデコンパイル結果が欠けていて、ECU から NCD を読む処理の中身は見えていない。挙動は実機で確認する。
- **実機での確認事項**: ZGW の実際のポートと URL、batch の `-readncd` の出力ファイル形式、長い PSdZ 呼び出しを途中で止められるか。
