package voice.core.data.folders

public enum class FolderType {
  SingleFile,
  SingleFolder,
  Root,
  Author,

  /** Books are wherever [voice.core.data.layout.detectBooks] finds them, whatever the folder layout. */
  Smart,
}
