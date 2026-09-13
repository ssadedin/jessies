Third-party jars. Everything matching lib/jars/*.jar is put on the compile
class path by universal.make and on the run-time class path by invoke-java.rb,
and lib/ is included in the installers.

gson-2.14.0.jar
  Google Gson 2.14.0, Apache License 2.0 (https://github.com/google/gson).
  Used by terminator.llm for OpenAI-compatible API requests and responses.
  From Maven Central: com.google.code.gson:gson:2.14.0
  SHA-1: efc0e34ede4e3204eaefb84a00e55e8c86634382

error_prone_annotations-2.48.0.jar
  Error Prone annotations 2.48.0, Apache License 2.0 (https://github.com/google/error-prone).
  A dependency of Gson: only needed at compile time, where javac otherwise warns
  about Gson's annotations ("Cannot find annotation method ... in type 'InlineMe'").
  From Maven Central: com.google.errorprone:error_prone_annotations:2.48.0
  SHA-1: bbe30942a31d1778c74cb272321267eca82dc708
