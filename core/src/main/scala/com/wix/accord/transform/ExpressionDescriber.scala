/*
  Copyright 2013-2015 Wix.com

  Licensed under the Apache License, Version 2.0 (the "License");
  you may not use this file except in compliance with the License.
  You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.
 */

package com.wix.accord.transform

import MacroHelper._
import com.wix.accord.Descriptions
import com.wix.accord.Descriptions.Description

import scala.language.experimental.macros



/** A macro helper trait that generates implicit description for expressions. The transformation operates in the
  * context of a function of the form `Function1[ T, U ]`, or in other words only supports single-parameter
  * functions.
  *
  * The expression is transformable via [[com.wix.accord.transform.ExpressionDescriber.describeTree]]
  * based on the following rules:
  *  - Selectors over the function prototype are rewritten to the selected expression; for example,
  *    `{ p: Person => p.firstName }` gets rewritten to a tree representing `AccessChain( "firstName" )`
  *  - Explicitly described expressions (via [[com.wix.accord.dsl.Descriptor]]) are rewritten to a tree
  *    representing the description as a string literal, for example `{ p: Person => p.firstName as "first name" }`
  *    gets rewritten as `Explicit( "first name" )`
  *  - Any other expression is rewritten as tree representing the expression itself, for
  *    example `{ _ => 1 + 2 + 3 }` gets rewritten as `Generic( "1 + 2 + 3" )`.
  *
  * @tparam C The macro context type
  */
private[ transform ] trait ExpressionDescriber[ C <: Context ] extends MacroHelper[ C ] with PatternHelper[ C ] {
  import Descriptions._
  import context.universe._

  /** An extractor for explicitly described expressions. Applies expressions like
    * `p.firstName as "described"`, where the `as` parameter (`"described"` in this case) is the extracted
    * description tree.
    */
  case object ExplicitlyDescribed {
    private val descriptorTerm = typeOf[ com.wix.accord.dsl.Descriptor[_] ].typeSymbol.name.toTermName
    private val asTerm = termName( "as" )

    private[ ExpressionDescriber ] def unapply( ouv: Tree ): Option[ context.Expr[ Explicit ] ] = ouv match {
      case Apply( Select( Apply( TypeApply( Select( _, `descriptorTerm` ), _ ), _ ), `asTerm` ), literal :: Nil ) =>
        Some( context.Expr[ Explicit ]( q"com.wix.accord.Descriptions.Explicit( $literal )" ) )
      case _ => None
    }
  }

  /** Generates a description for the specified AST.
    *
    * @param prototype The function prototype; specifically, the single function parameter's definition as
    *                  a `ValDef`. Must be provided by the inheritor.
    * @return The generated description.
    */
  protected def describeTree( prototype: ValDef, ouv: Tree ): context.Expr[ Description ] = {
    val PrototypeName = prototype.name

    /** A helper extractor object that handles selector chains recursively. The innermost selector must select
      * over the function prototype.
      *
      * For example, for the function `{ p: Person => p.firstName.size }` and the input representing the tree of the
      * function definition, the AST will look like this:
      *
      *  `Select(Select(Ident(newTermName("p")), newTermName("firstName")), newTermName("size"))`
      *
      * This in turn gets extracted as `PrototypeSelectorChain( "firstName" :: "size" :: Nil )`.
      */
    object PrototypeSelectorChain {
      def unapplySeq( ouv: Tree ): Option[ Seq[ Name ] ] = ouv match {
        case Select( Ident( PrototypeName ), selector ) => Some( selector :: Nil )
        case Select( PrototypeSelectorChain( elements @ _* ), selector ) => Some( elements :+ selector )
        case _ => None
      }
    }

    ouv match {
      case ExplicitlyDescribed( description ) =>
        description

      case PrototypeSelectorChain( elements @ _* ) =>
        def renderName( n: Name ) = n.decodedName.toString
        context.Expr[ Description ]( q"com.wix.accord.Descriptions.AccessChain( ..${ elements map renderName } )" )

      case Ident( PrototypeName ) =>
        // Anonymous parameter reference: validator[...] { _ is... }
        context.Expr[ Description ]( q"com.wix.accord.Descriptions.SelfReference" )

      case _ =>
        context.Expr[ Description ]( q"com.wix.accord.Descriptions.Generic( ${ ouv.toString } )" )
    }
  }
}

/** A helper class which builds on [[com.wix.accord.transform.ExpressionDescriber]] to describe function literals. */
private[ transform ] trait FunctionDescriber[ C <: Context, T, U ]
  extends ExpressionDescriber[ C ]
{
  import context.universe._

  def describeFunction( f: Expr[ T => U ] ): ( ValDef, Tree ) =
    f.tree match {
      case Function( proto :: Nil, impl ) =>
        ( proto, impl )

      case Function( _ :: tail, _ ) if tail != Nil =>
        context.abort( tail.head.pos, "Only single-parameter functions are supported!" )

      case _ =>
        context.abort( f.tree.pos,
          """
            |Only function literals are supported; function parameters (val f: T => U = ...) cannot be resolved at
            |compile time.
          """.stripMargin )
    }
}

private class TestFunctionDescriber[ C <: Context, T, U ]( val context: C, f: C#Expr[ T => U ] )
  extends FunctionDescriber[ C, T, U ]
{
  import context.universe._

  val ( prototype, implementation ) = describeFunction( f in context.mirror )

  /** Renders a description for the function body and externalizes it as a string expression. */
  def renderedDescription: Expr[ Description ] = describeTree( prototype, implementation )
}

private[ accord ] object ExpressionDescriber {

  def apply[ T : c.WeakTypeTag, U : c.WeakTypeTag ]( c: Context )( f: c.Expr[ T => U ] ): c.Expr[ Description ] =
    new TestFunctionDescriber[ c.type, T, U ]( c, f ).renderedDescription

  /** A test invoker for [[com.wix.accord.transform.ExpressionDescriber]] */
  def describe[ T, U ]( f: T => U ): Description = macro ExpressionDescriber[ T, U ]
}
