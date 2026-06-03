最大回数の表示ラベルが2行に折り返していたため、1行固定に変更した。

変更内容:
・最大回数ラベルに `maxLines = 1` を追加。
・`softWrap = false` と `TextOverflow.Ellipsis` を設定して折り返しを防止。

確認:
・.\gradlew.bat :app:assembleDebug 成功。
