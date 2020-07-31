package com.apollographql.apollo.compiler.parser.sdl

import com.apollographql.apollo.compiler.ir.SourceLocation
import com.apollographql.apollo.compiler.parser.antlr.GraphSDLLexer
import com.apollographql.apollo.compiler.parser.antlr.GraphSDLParser
import com.apollographql.apollo.compiler.parser.error.DocumentParseException
import com.apollographql.apollo.compiler.parser.error.ParseException
import org.antlr.v4.runtime.ANTLRInputStream
import org.antlr.v4.runtime.BaseErrorListener
import org.antlr.v4.runtime.CommonTokenStream
import org.antlr.v4.runtime.RecognitionException
import org.antlr.v4.runtime.Recognizer
import org.antlr.v4.runtime.Token
import org.antlr.v4.runtime.atn.PredictionMode
import java.io.File
import java.io.IOException
import java.util.Locale

internal object GraphSDLSchemaParser {
  private val builtInScalarTypes = listOf(
      GraphSdlSchema.TypeDefinition.Scalar(
          name = "Int",
          description = "The `Int` scalar type represents non-fractional signed whole numeric values. " +
              "Int can represent values between -(2^31) and 2^31 - 1. ",
          directives = emptyList()
      ),
      GraphSdlSchema.TypeDefinition.Scalar(
          name = "Float",
          description = "The `Float` scalar type represents signed double-precision fractional values as specified by " +
              "[IEEE 754](http://en.wikipedia.org/wiki/IEEE_floating_point).",
          directives = emptyList()
      ),
      GraphSdlSchema.TypeDefinition.Scalar(
          name = "String",
          description = "The `String` scalar type represents textual data, represented as UTF-8 character sequences. " +
              "The String type is most often used by GraphQL to represent free-form human-readable text.",
          directives = emptyList()
      ),
      GraphSdlSchema.TypeDefinition.Scalar(
          name = "Boolean",
          description = "The `Boolean` scalar type represents `true` or `false`.",
          directives = emptyList()
      ),
      GraphSdlSchema.TypeDefinition.Scalar(
          name = "ID",
          description = "The `ID` scalar type represents a unique identifier, often used to refetch an object or as key for a cache. " +
              "The ID type appears in a JSON response as a String; however, it is not intended to be human-readable. " +
              "When expected as an input type, any string (such as `\"4\"`) or integer (such as `4`) input value will be accepted as an ID.",
          directives = emptyList()
      )
  )

  fun File.parse(): GraphSdlSchema {
    val document = try {
      readText()
    } catch (e: IOException) {
      throw RuntimeException("Failed to read GraphQL SDL schema file `$this`", e)
    }

    val tokenStream = GraphSDLLexer(ANTLRInputStream(document))
        .apply { removeErrorListeners() }
        .let { CommonTokenStream(it) }

    val parser = GraphSDLParser(tokenStream).apply {
      removeErrorListeners()
      interpreter.predictionMode = PredictionMode.SLL
      addErrorListener(
          object : BaseErrorListener() {
            override fun syntaxError(
                recognizer: Recognizer<*, *>?,
                offendingSymbol: Any?,
                line: Int,
                position: Int,
                msg: String?,
                e: RecognitionException?
            ) {
              throw DocumentParseException(
                  message = "Unsupported token `${(offendingSymbol as? Token)?.text ?: offendingSymbol.toString()}`",
                  filePath = absolutePath,
                  sourceLocation = SourceLocation(
                      line = line,
                      position = position
                  )
              )
            }
          }
      )
    }

    try {
      return parser.document().parse()
    } catch (e: ParseException) {
      throw DocumentParseException(
          parseException = e,
          filePath = absolutePath
      )
    }
  }

  private fun GraphSDLParser.DocumentContext.parse(): GraphSdlSchema {
    val typeDefinitions = typeDefinition()
        ?.flatMap { ctx ->
          listOfNotNull(
              ctx.enumTypeDefinition()?.parse(),
              ctx.objectTypeDefinition()?.parse(),
              ctx.interfaceTypeDefinition()?.parse(),
              ctx.inputObjectDefinition()?.parse(),
              ctx.unionTypeDefinition()?.parse(),
              ctx.scalarTypeDefinition()?.parse()
          )
        }
        ?.plus(builtInScalarTypes)
        ?.associateBy { it.name }

    val schemaDefinition = schemaDefinition().firstOrNull()
    val operationRootTypes = schemaDefinition?.operationTypesDefinition().parse()
    return GraphSdlSchema(
        schema = GraphSdlSchema.Schema(
            description = schemaDefinition?.description()?.parse(),
            directives = schemaDefinition?.directives().parse(),
            queryRootOperationType = GraphSdlSchema.TypeRef.Named(operationRootTypes["query"] ?: "Query", SourceLocation(start)),
            mutationRootOperationType = GraphSdlSchema.TypeRef.Named(operationRootTypes["mutation"] ?: "Mutation", SourceLocation(start)),
            subscriptionRootOperationType = GraphSdlSchema.TypeRef.Named(
                operationRootTypes["subscription"] ?: "Subscription", SourceLocation(start)
            )
        ),
        typeDefinitions = typeDefinitions ?: emptyMap()
    )
  }

  private fun GraphSDLParser.OperationTypesDefinitionContext?.parse(): Map<String, String> {
    return this
        ?.operationTypeDefinition()
        ?.map { it.operationType().text to it.namedType().text }
        ?.toMap()
        ?: emptyMap()
  }

  private fun GraphSDLParser.EnumTypeDefinitionContext.parse(): GraphSdlSchema.TypeDefinition.Enum {
    return GraphSdlSchema.TypeDefinition.Enum(
        name = name().text,
        description = description().parse(),
        directives = directives().parse(),
        enumValues = enumValuesDefinition().parse()
    )
  }

  private fun GraphSDLParser.EnumValuesDefinitionContext?.parse(): List<GraphSdlSchema.TypeDefinition.Enum.Value> {
    return this
        ?.enumValueDefinition()
        ?.map { it.parse() }
        ?: emptyList();
  }

  private fun GraphSDLParser.EnumValueDefinitionContext.parse(): GraphSdlSchema.TypeDefinition.Enum.Value {
    return GraphSdlSchema.TypeDefinition.Enum.Value(
        name = name().text,
        description = description().parse(),
        directives = directives().parse()
    )
  }

  private fun GraphSDLParser.ObjectTypeDefinitionContext.parse(): GraphSdlSchema.TypeDefinition.Object {
    return GraphSdlSchema.TypeDefinition.Object(
        name = name().text,
        description = description().parse(),
        directives = directives().parse(),
        fields = fieldsDefinition().parse(),
        interfaces = implementsInterfaces().parse()
    )
  }

  private fun GraphSDLParser.InterfaceTypeDefinitionContext.parse(): GraphSdlSchema.TypeDefinition.Interface {
    return GraphSdlSchema.TypeDefinition.Interface(
        name = name().text,
        description = description().parse(),
        directives = directives().parse(),
        fields = fieldsDefinition().parse()
    )
  }

  private fun GraphSDLParser.InputObjectDefinitionContext.parse(): GraphSdlSchema.TypeDefinition.InputObject {
    return GraphSdlSchema.TypeDefinition.InputObject(
        name = name().text,
        description = description().parse(),
        directives = directives().parse(),
        fields = inputValuesDefinition().parse()
    )
  }

  private fun GraphSDLParser.UnionTypeDefinitionContext.parse(): GraphSdlSchema.TypeDefinition.Union {
    return GraphSdlSchema.TypeDefinition.Union(
        name = name().text,
        description = description().parse(),
        directives = directives().parse(),
        typeRefs = unionMemberTypes()?.unionMemberType()?.map { it.namedType().parse() } ?: emptyList()
    )
  }

  private fun GraphSDLParser.ScalarTypeDefinitionContext.parse(): GraphSdlSchema.TypeDefinition.Scalar {
    return GraphSdlSchema.TypeDefinition.Scalar(
        name = name().text,
        description = description().parse(),
        directives = directives().parse()
    )
  }

  private fun GraphSDLParser.ImplementsInterfacesContext?.parse(): List<GraphSdlSchema.TypeRef.Named> {
    return this
        ?.implementsInterface()
        ?.map { GraphSdlSchema.TypeRef.Named(it.namedType().text, SourceLocation(start)) }
        ?: emptyList()
  }

  private fun GraphSDLParser.FieldsDefinitionContext?.parse(): List<GraphSdlSchema.TypeDefinition.Field> {
    return this
        ?.fieldDefinition()
        ?.map { it.parse() }
        ?: emptyList()
  }

  private fun GraphSDLParser.FieldDefinitionContext.parse(): GraphSdlSchema.TypeDefinition.Field {
    return GraphSdlSchema.TypeDefinition.Field(
        name = name().text,
        description = description().parse(),
        directives = directives().parse(),
        type = type().parse(),
        arguments = argumentsDefinition().parse()
    )
  }

  private fun GraphSDLParser.ArgumentsDefinitionContext?.parse(): List<GraphSdlSchema.TypeDefinition.Field.Argument> {
    return this
        ?.argumentDefinition()
        ?.map { it.parse() }
        ?: emptyList()
  }

  private fun GraphSDLParser.ArgumentDefinitionContext.parse(): GraphSdlSchema.TypeDefinition.Field.Argument {
    return GraphSdlSchema.TypeDefinition.Field.Argument(
        name = name().text,
        description = description().parse(),
        directives = directives().parse(),
        type = type().parse(),
        defaultValue = defaultValue()?.value()?.parse()
    )
  }

  private fun GraphSDLParser.InputValuesDefinitionContext?.parse(): List<GraphSdlSchema.TypeDefinition.InputField> {
    return this
        ?.inputValueDefinition()
        ?.map { it.parse() }
        ?: emptyList()
  }

  private fun GraphSDLParser.InputValueDefinitionContext.parse(): GraphSdlSchema.TypeDefinition.InputField {
    return GraphSdlSchema.TypeDefinition.InputField(
        name = name().text,
        description = description().parse(),
        directives = directives().parse(),
        type = type().parse(),
        defaultValue = defaultValue()?.value()?.parse()
    )
  }

  private fun GraphSDLParser.ValueContext.parse(): Any? {
    return when {
      intValue() != null -> intValue().INT().text.toInt()
      floatValue() != null -> floatValue().FLOAT().text.toDouble()
      booleanValue() != null -> booleanValue().text == "true"
      enumValue() != null -> enumValue().name().text
      listValue() != null -> listValue().value().map { it.parse() }
      objectValue() != null -> text
      stringValue() != null -> text
      nullValue() != null -> null
      else -> throw ParseException(
          message = "Illegal default value `$text`",
          token = start
      )
    }
  }

  private fun GraphSDLParser.TypeContext.parse(): GraphSdlSchema.TypeRef {
    return when {
      namedType() != null -> namedType().parse()
      listType() != null -> listType().parse()
      nonNullType() != null -> nonNullType().parse()
      else -> throw ParseException(
          message = "Illegal type reference",
          token = start
      )
    }
  }

  private fun GraphSDLParser.NamedTypeContext.parse(): GraphSdlSchema.TypeRef.Named {
    return GraphSdlSchema.TypeRef.Named(name().text, sourceLocation = SourceLocation(start))
  }

  private fun GraphSDLParser.ListTypeContext.parse(): GraphSdlSchema.TypeRef.List {
    return GraphSdlSchema.TypeRef.List(type().parse())
  }

  private fun GraphSDLParser.NonNullTypeContext.parse(): GraphSdlSchema.TypeRef.NonNull {
    return when {
      namedType() != null -> GraphSdlSchema.TypeRef.NonNull(namedType().parse())
      listType() != null -> GraphSdlSchema.TypeRef.NonNull(listType().parse())
      else -> throw ParseException(
          message = "Illegal type reference",
          token = this.start
      )
    }
  }

  private fun GraphSDLParser.DescriptionContext?.parse(): String {
    return this?.STRING()?.text?.removePrefix("\"")?.removePrefix("\n")?.removeSuffix("\"")?.removeSuffix("\n")
        ?: this?.BLOCK_STRING()?.text?.removePrefix("\"\"\"")?.removePrefix("\n")?.removeSuffix("\"\"\"")?.removeSuffix("\n")
        ?: ""
  }

  private fun GraphSDLParser.DirectivesContext?.parse(): List<GraphSdlSchema.Directive> {
    return this
        ?.directive()
        ?.map { it.parse() }
        ?: emptyList()
  }

  private fun GraphSDLParser.DirectiveContext.parse(): GraphSdlSchema.Directive {
    return GraphSdlSchema.Directive(
        name = name().text,
        arguments = directiveArguments().parse()
    )
  }

  private fun GraphSDLParser.DirectiveArgumentsContext?.parse(): Map<String, String> {
    return this
        ?.directiveArgument()
        ?.map { it.name().text.toLowerCase(Locale.ENGLISH) to it.value().text }
        ?.toMap()
        ?: emptyMap()
  }
}

private operator fun SourceLocation.Companion.invoke(token: Token) = SourceLocation(
    line = token.line,
    position = token.charPositionInLine
)
