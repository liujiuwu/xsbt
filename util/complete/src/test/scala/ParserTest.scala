package sbt.parse

	import Parser._

object ParserExample
{
	val ws = charClass(_.isWhitespace)+
	val notws = charClass(!_.isWhitespace)+

	val name = token("test")
	val options = (ws ~ token("quick" || "failed" || "new") )*
	val include = (ws ~ token(examples(notws, Set("am", "is", "are", "was", "were") )) )*

	val t = name ~ options ~ include

	// Get completions for some different inputs
	println(completions(t, "te"))
	println(completions(t, "test "))
	println(completions(t, "test w"))

	// Get the parsed result for different inputs
	println(apply(t)("te").resultEmpty)
	println(apply(t)("test").resultEmpty)
	println(apply(t)("test w").resultEmpty)
	println(apply(t)("test was were").resultEmpty)
}