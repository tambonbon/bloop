package bloop.io

import java.io.IOException
import java.nio.file.DirectoryNotEmptyException
import java.nio.file.FileSystems
import java.nio.file.FileVisitOption
import java.nio.file.FileVisitResult
import java.nio.file.FileVisitor
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.{Paths => NioPaths}
import java.time.temporal.ChronoUnit
import java.util

import scala.collection.mutable
import scala.util.Properties

import dev.dirs.ProjectDirectories
import dev.dirs.jni.WindowsJni

object Paths {
  private def createDirFor(filepath: String): AbsolutePath =
    AbsolutePath(Files.createDirectories(NioPaths.get(filepath)))

  val projDirs = ProjectDirectories.from(null, null, "bloop", WindowsJni.getJdkAwareSupplier())
  private lazy val bloopCacheDir: AbsolutePath = createDirFor(projDirs.cacheDir)
  private lazy val bloopDataDir: AbsolutePath = createDirFor(projDirs.dataDir)

  lazy val daemonDir: AbsolutePath = {
    def defaultDir = {
      val baseDir =
        if (Properties.isMac) bloopCacheDir.underlying
        else bloopDataDir.underlying
      baseDir.resolve("daemon")
    }
    val dir = Option(System.getenv("BLOOP_DAEMON_DIR")).filter(_.trim.nonEmpty) match {
      case Some(dirStr) => java.nio.file.Paths.get(dirStr)
      case None => defaultDir
    }
    if (!Files.exists(dir)) {
      Files.createDirectories(dir)
      if (!Properties.isWin)
        Files.setPosixFilePermissions(
          dir,
          PosixFilePermissions.fromString("rwx------")
        )
    }
    AbsolutePath(dir)
  }

  def getCacheDirectory(dirName: String): AbsolutePath = {
    val dir = bloopCacheDir.resolve(dirName)
    val dirPath = dir.underlying
    if (!Files.exists(dirPath)) Files.createDirectories(dirPath)
    else require(Files.isDirectory(dirPath), s"File '${dir.syntax}' is not a directory.")
    dir
  }

  /**
   * Get all files under `base` that match the pattern `pattern` up to depth `maxDepth`.
   *
   * Example:
   * ```
   * Paths.pathFilesUnder(src, "glob:**.{scala,java}")
   * ```
   */
  def pathFilesUnder(
      base: AbsolutePath,
      pattern: String,
      maxDepth: Int = Int.MaxValue
  ): List[AbsolutePath] = {
    val out = collection.mutable.ListBuffer.empty[AbsolutePath]
    val matcher = FileSystems.getDefault.getPathMatcher(pattern)

    val visitor = new FileVisitor[Path] {
      def visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult = {
        if (matcher.matches(file)) out += AbsolutePath(file)
        FileVisitResult.CONTINUE
      }

      def visitFileFailed(
          t: Path,
          e: IOException
      ): FileVisitResult = FileVisitResult.CONTINUE

      def preVisitDirectory(
          directory: Path,
          attributes: BasicFileAttributes
      ): FileVisitResult = FileVisitResult.CONTINUE

      def postVisitDirectory(
          directory: Path,
          exception: IOException
      ): FileVisitResult = FileVisitResult.CONTINUE
    }

    val opts = util.EnumSet.of(FileVisitOption.FOLLOW_LINKS)
    Files.walkFileTree(base.underlying, opts, maxDepth, visitor)
    out.toList
  }

  // sealed abstract is an abomination for scala 2.12 to get private which actually works
  sealed abstract case class AttributedPath(
      path: AbsolutePath,
      lastModifiedTime: FileTime,
      size: Long
  ) {
    def withPath(newPath: AbsolutePath): AttributedPath =
      new AttributedPath(newPath, lastModifiedTime, size) {}
  }

  object AttributedPath {
    def of(path: AbsolutePath, lastModifiedTime: FileTime, size: Long): AttributedPath = {
      // this logic exists to maintain consistency between old and new JVMs. Older JVMs does not provide more than millisecond precision for `Instant`s by default
      val truncatedFileTime =
        FileTime.from(lastModifiedTime.toInstant.truncatedTo(ChronoUnit.MILLIS))
      new AttributedPath(path, truncatedFileTime, size) {}
    }
  }

  /**
   * Get all files under `base` that match the pattern `pattern` up to depth `maxDepth`.
   *
   * The returned list of traversed files contains a last modified time attribute. This
   * is a duplicated version of [[pathFilesUnder]] that returns the last modified time
   * attribute for performance reasons, since the nio API exposes the attributed in the
   * visitor and minimizes the number of underlying file system calls.
   *
   * Example:
   * ```
   * Paths.getAll(src, "glob:**.{scala,java}")
   * ```
   */
  def attributedPathFilesUnder(
      base: AbsolutePath,
      pattern: String,
      logger: xsbti.Logger,
      maxDepth: Int = Int.MaxValue,
      ignoreVisitErrors: Boolean = false
  ): List[AttributedPath] = {
    val out = collection.mutable.ListBuffer.empty[AttributedPath]
    val matcher = FileSystems.getDefault.getPathMatcher(pattern)

    val visitor = new FileVisitor[Path] {
      def visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult = {
        if (matcher.matches(file)) {
          out += AttributedPath.of(
            AbsolutePath(file),
            // Truncate to milliseconds, to workaround precision discrepancy issues in the tests
            FileTime.fromMillis(attributes.lastModifiedTime().toMillis),
            attributes.size()
          )
        }
        FileVisitResult.CONTINUE
      }

      def visitFileFailed(t: Path, e: IOException): FileVisitResult = {
        if (!ignoreVisitErrors) {
          logger.error(() => s"Unexpected failure when visiting ${t}: '${e.getMessage}'")
          logger.trace(() => e)
        }
        FileVisitResult.CONTINUE
      }

      def preVisitDirectory(
          directory: Path,
          attributes: BasicFileAttributes
      ): FileVisitResult = FileVisitResult.CONTINUE

      def postVisitDirectory(
          directory: Path,
          exception: IOException
      ): FileVisitResult = FileVisitResult.CONTINUE
    }

    if (!base.exists) Nil
    else {
      val opts = util.EnumSet.of(FileVisitOption.FOLLOW_LINKS)
      Files.walkFileTree(base.underlying, opts, maxDepth, visitor)
      out.toList
    }
  }

  /**
   * Recursively delete `path` and all its content.
   *
   * Ignores any IO error that happens related to the deletion.
   *
   * @param path The path to delete
   */
  def delete(path: AbsolutePath): Unit = {
    try {
      Files.walkFileTree(
        path.underlying,
        new SimpleFileVisitor[Path] {
          override def visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult = {
            Files.delete(file)
            FileVisitResult.CONTINUE
          }

          override def postVisitDirectory(dir: Path, exc: IOException): FileVisitResult = {
            try Files.delete(dir)
            catch { case _: DirectoryNotEmptyException => () } // Happens sometimes on Windows?
            FileVisitResult.CONTINUE
          }
        }
      )
    } catch { case _: IOException => () }
    ()
  }

  def isDirectoryEmpty(path: AbsolutePath, excludeDirs: Boolean): Boolean = {
    var isEmpty: Boolean = true
    import java.nio.file.NoSuchFileException
    try {
      Files.walkFileTree(
        path.underlying,
        new SimpleFileVisitor[Path] {
          override def visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult = {
            isEmpty = false
            FileVisitResult.TERMINATE
          }

          override def preVisitDirectory(
              directory: Path,
              attributes: BasicFileAttributes
          ): FileVisitResult = {
            if (excludeDirs || path.underlying == directory) FileVisitResult.CONTINUE
            else {
              isEmpty = false
              FileVisitResult.TERMINATE
            }
          }
        }
      )
    } catch {
      case _: NoSuchFileException => isEmpty = true
    }
    isEmpty
  }

  /**
   * Lists all top-level children of a path, none if the path doesn't exist.
   *
   * Use this function always instead of using `Files.list` directly and
   * wrapping it with collection converters. `Files.list` returns a stream that
   * it is never closed otherwise, creating a lot of open file handles to
   * directories that can make bloop crash.
   */
  def list(path: AbsolutePath): List[AbsolutePath] = {
    val topLevelChildren = new mutable.ListBuffer[AbsolutePath]
    val pathStream = Files.list(path.underlying)
    try {
      pathStream.forEach { path =>
        topLevelChildren.+=(AbsolutePath(path))
      }
    } catch {
      case _: NoSuchFileException => ()
    } finally {
      pathStream.close()
    }
    topLevelChildren.toList
  }
}
