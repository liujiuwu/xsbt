package sbt
package appmacro

	import Types.Id
	import scala.tools.nsc.Global
	import scala.reflect._
	import makro._

/** A `TupleBuilder` that uses a KList as the tuple representation.*/
object KListBuilder extends TupleBuilder
{
	def make(c: Context)(mt: c.Type, inputs: Inputs[c.universe.type]): BuilderResult[c.type] = new BuilderResult[c.type]
	{
		val ctx: c.type = c
		val util = ContextUtil[c.type](c)
			import c.universe.{Apply=>ApplyTree,_}
			import util._

		val knilType = c.typeOf[KNil]
		val knil = Ident(knilType.typeSymbol.companionSymbol)
		val kconsTpe = c.typeOf[KCons[Int,KNil,List]]
		val kcons = kconsTpe.typeSymbol.companionSymbol
		val mTC: Type = mt.asInstanceOf[c.universe.Type]
		val kconsTC: Type = kconsTpe.typeConstructor

		/** This is the L in the type function [L[x]] ...  */
		val tcVariable: Symbol = newTCVariable(NoSymbol)

		/** Instantiates KCons[h, t <: KList[L], L], where L is the type constructor variable */
		def kconsType(h: Type, t: Type): Type =
			appliedType(kconsTC, h :: t :: refVar(tcVariable) :: Nil)

		def bindKList(prev: ValDef, revBindings: List[ValDef], params: List[ValDef]): List[ValDef] =
			params match
			{
				case ValDef(mods, name, tpt, _) :: xs =>
					val head = ValDef(mods, name, tpt, Select(Ident(prev.name), "head"))
					val tail = localValDef(TypeTree(), Select(Ident(prev.name), "tail"))
					val base = head :: revBindings
					bindKList(tail, if(xs.isEmpty) base else tail :: base, xs)
				case Nil => revBindings.reverse
			}

		/** The input trees combined in a KList */
		val klist = (inputs :\ (knil: Tree))( (in, klist) => ApplyTree(kcons, in.expr, klist) )

		/** The input types combined in a KList type.  The main concern is tracking the heterogeneous types.
		* The type constructor is tcVariable, so that it can be applied to [X] X or M later.  
		* When applied to `M`, this type gives the type of the `input` KList. */
		val klistType: Type = (inputs :\ knilType)( (in, klist) => kconsType(in.tpe, klist) )

		val representationC = PolyType(tcVariable :: Nil, klistType)
		val resultType = appliedType(representationC, idTC :: Nil)
		val input = klist
		val alistInstance = TypeApply(Select(Ident(alist), "klist"), typeTree(representationC) :: Nil)
		def extract(param: ValDef) = bindKList(param, Nil, inputs.map(_.local))
	}
}