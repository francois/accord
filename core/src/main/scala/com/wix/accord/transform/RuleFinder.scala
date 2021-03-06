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
import com.wix.accord._

private[ transform ] trait RuleFinder[ C <: Context ] extends PatternHelper[ C ] with ExpressionDescriber[ C ] {
  self: MacroLogging[ C ] =>

  import context.universe._
  import context.abort

  sealed trait ValidatorApplication
  protected case class BooleanExpression( expr: Tree ) extends ValidatorApplication
  protected case class ValidationRule( ouv: Tree, validation: Tree ) extends ValidatorApplication

  /** An extractor for validation rules. The object under validation is, by design, wrapped in the implicit
    * DSL construct [[com.wix.accord.dsl.Contextualizer]], so that a validation rule can be defined with
    * syntax like `p.firstName is notEmpty`.
    *
    * In the example above, `p.firstName` is the expression wrapped by [[com.wix.accord.dsl.Contextualizer]]
    * and yields the Object Under Validation (OUV).
    */
  object ValidatorApplication {
    private val contextualizerTerm = typeOf[ dsl.Contextualizer[_] ].typeSymbol.name.toTermName

    private def extractObjectUnderValidation( t: Tree ): List[ Tree ] =
      collectFromPattern( t ) {
        case Apply( TypeApply( Select( _, `contextualizerTerm` ), tpe :: Nil ), e :: Nil ) =>
          resetAttrs( e.duplicate )
      }

    private object AtLeastOneSelect {
      private def unapplyInternal( tree: Tree ): Option[ Tree ] = tree match {
        case Select( from, _ ) => unapplyInternal( from )
        case terminal => Some( terminal )
      }

      def unapply( tree: Tree ): Option[ Tree ] = tree match {
        case Select( from, _ ) => unapplyInternal( from )
        case terminal => None
      }
    }

    private def rewriteContextExpressionAsValidator( expr: Tree ) =
      transformByPattern( expr ) {
        case root @ Apply( AtLeastOneSelect( Apply( TypeApply( Select( _, `contextualizerTerm` ), _ ), _ :: Nil ) ), _ :: Nil ) =>
          rewriteExistentialTypes( root )
      }

    object TypedRule {
      def unapply( t: Tree ): Option[ Tree ] =
        if ( !t.tpe.isBottom && t.tpe <:< typeOf[ Validator[_] ] )
          Some( t )
        else
          None
    }

    object ObjectUnderValidation {
      def unapply( t: Tree ): Option[ List[ Tree ] ] =
        TypedRule.unapply( t ) map extractObjectUnderValidation
    }

    def unapply( expr: Tree ): Option[ ValidatorApplication ] = expr match {
      case ObjectUnderValidation( Nil ) =>
        abort( expr.pos, s"Failed to extract object under validation from tree $expr (type=${expr.tpe}, raw=${showRaw(expr)})" )

      case ObjectUnderValidation( ouv :: Nil ) =>
        val sv = rewriteContextExpressionAsValidator( expr )
        trace( s"""
              |Found validation rule:
              |  ouv=$ouv
              |  ouvraw=${showRaw(ouv)}
              |  sv=${show(sv)}
              |  svraw=${showRaw(sv)}
              |""".stripMargin, ouv.pos )
        Some( ValidationRule( ouv, sv ) )

      case ObjectUnderValidation( _ :: _ ) =>
        // Multiple validators found; this can happen in case of a multiple-clause boolean expression,
        // e.g. "(f1 is notEmpty) or (f2 is notEmpty)".
        Some( BooleanExpression( expr ) )

      case _ => None
    }
  }
}
