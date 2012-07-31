package sbt
package std

	import Def.{Initialize,Setting}
	import Types.{idFun,Id}
	import appmacro.{Convert, Instance, MixedBuilder, MonadInstance}

object InitializeInstance extends MonadInstance
{
	type M[x] = Initialize[x]
	def app[K[L[x]], Z](in: K[Initialize], f: K[Id] => Z)(implicit a: AList[K]): Initialize[Z] = Def.app[K,Z](in)(f)(a)
	def map[S,T](in: Initialize[S], f: S => T): Initialize[T] = Def.map(in)(f)
	def flatten[T](in: Initialize[Initialize[T]]): Initialize[T] = Def.bind(in)(idFun[Initialize[T]])
	def pure[T](t: () => T): Initialize[T] = Def.pure(t)
}
object InitializeConvert extends Convert
{
	def apply[T: c.TypeTag](c: reflect.makro.Context)(in: c.Tree): c.Tree =
		if(in.tpe <:< c.typeOf[Initialize[Task[T]]] || in.tpe <:< c.typeOf[Task[T]])
			c.abort(in.pos, "A setting cannot depend on a task")
		else if(in.tpe <:< c.typeOf[Initialize[T]])
		{
			val i = c.Expr[Initialize[T]](in)
			c.universe.reify( i.splice ).tree
		}
		else
			c.abort(in.pos, "Unknown input type: " + in.tpe)
}

	import language.experimental.macros
	import scala.reflect._
	import makro._

object SettingMacro
{
	def setting[T](t: T): Initialize[T] = macro settingMacroImpl[T]
	def settingMacroImpl[T: c.TypeTag](c: Context)(t: c.Expr[T]): c.Expr[Initialize[T]] =
		Instance.contImpl[T](c, InitializeInstance, InitializeConvert, MixedBuilder)(Left(t))

	def settingDyn[T](t: Initialize[T]): Initialize[T] = macro settingDynMacroImpl[T]
	def settingDynMacroImpl[T: c.TypeTag](c: Context)(t: c.Expr[Initialize[T]]): c.Expr[Initialize[T]] = 
		Instance.contImpl[T](c, InitializeInstance, InitializeConvert, MixedBuilder)(Right(t))
}
