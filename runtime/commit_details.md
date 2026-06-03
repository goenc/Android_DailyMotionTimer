通常カウントの間隔切り替えを追加した。

変更内容:
・通常カウント用にSmall、Medium、Largeの3段階間隔を追加した。
・Mediumを既定値とし、Smallはやや速く、Largeはやや遅い間隔で進行するようにした。
・カウントタブのメイン画面で間隔を選べるようにし、待機中のみ変更可能にした。
・選択した間隔をDataStoreへ保存するようにした。

確認:
・.\gradlew.bat :app:assembleDebug 成功。
