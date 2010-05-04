/*
* JBoss, Home of Professional Open Source
* Copyright 2008, Red Hat Middleware LLC, and individual contributors
* by the @authors tag. See the copyright.txt in the distribution for a
* full listing of individual contributors.
*
* This is free software; you can redistribute it and/or modify it
* under the terms of the GNU Lesser General Public License as
* published by the Free Software Foundation; either version 2.1 of
* the License, or (at your option) any later version.
*
* This software is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
* Lesser General Public License for more details.
*
* You should have received a copy of the GNU Lesser General Public
* License along with this software; if not, write to the Free
* Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
* 02110-1301 USA, or see the FSF site: http://www.fsf.org.
*
* @authors Andrew Dinn
*/
package org.jboss.byteman.rule.binding;

import org.jboss.byteman.rule.type.Type;
import org.jboss.byteman.rule.expression.Expression;
import org.jboss.byteman.rule.exception.TypeException;
import org.jboss.byteman.rule.exception.ExecuteException;
import org.jboss.byteman.rule.exception.CompileException;
import org.jboss.byteman.rule.Rule;
import org.jboss.byteman.rule.RuleElement;
import org.jboss.byteman.rule.compiler.StackHeights;
import org.jboss.byteman.rule.helper.HelperAdapter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.StringWriter;

/**
 * Class used to store a binding of a named variable to a value of some given type
 */

public class Binding extends RuleElement
{

    public Binding(Rule rule, String name)
    {
        this(rule, name, Type.UNDEFINED, null);
    }

    public Binding(Rule rule, String name, Type type)
    {
        this(rule, name, type, null);
    }

    public Binding(Rule rule, String name, Type type, Expression value)
    {
        super(rule);
        this.name = name;
        this.type = (type != null ? type : Type.UNDEFINED);
        this.value = value;
        this.alias = null;
        // ok, check the name to see what type of binding we have
        if (name.matches("\\$[0-9].*")) {
            // $NNN references the method target or a parameter from 0 upwards
            index = Integer.valueOf(name.substring(1));
        } else if (name.equals("$$")) {
            // $$ references the helper implicitly associated with a builtin call
            index = HELPER;
        } else if (name.equals("$!")) {
            // $! refers to the current return value for the trigger method and is only valid when
            // the rule is triggered AT EXIT
            index = RETURN_VAR;
        } else if (name.equals("$^")) {
            // $^ refers to the current throwable value for the trigger method and is only valid when
            // the rule is triggered AT THROW
            index = THROWABLE_VAR;
        } else if (name.equals("$#")) {
            // $# refers to the parameter count for the trigger method
            index = PARAM_COUNT_VAR;
        } else if (name.equals("$*")) {
            // $* refers to the parameters for the trigger method supplied as an Object array
            index = PARAM_ARRAY_VAR;
        } else if (name.matches("\\$[A-Za-z].*")) {
           // $AAAAA refers  to a local variable in the trigger method
            index = LOCAL_VAR;
        } else {
            // anything else must be a variable introduced in the BINDS clause
            index = BIND_VAR;
        }
        this.callArrayIndex = 0;

        this.updated = false;
    }

    public Type typeCheck(Type expected)
            throws TypeException
    {
        if (alias != null) {
            return alias.typeCheck(expected);
        }
        
        // value can be null if this is a rule method parameter
        if (value != null) {
            // type check the binding expression, using the bound variable's expected if it is known

            if (type.isDefined()) {
                value.typeCheck(type);
                // redundant?
                if (Type.dereference(expected).isDefined() && !expected.isAssignableFrom(type)) {
                    throw new TypeException("Binding.typecheck : incompatible type binding expression " + type + value.getPos());
                }
            }  else {
                Type valueType = value.typeCheck(expected);

                type = valueType;
            }
        } else if (type.isUndefined()) {
            // can we have no expected for a method parameter?
            throw new TypeException("Binding.typecheck unknown type for binding " + name);
        }
        return type;
    }

    public Object interpret(HelperAdapter helper) throws ExecuteException
    {
        if (isBindVar()) {
            Object result = value.interpret(helper);
            helper.setBinding(getName(), result);
            return result;
        }
        return null;
    }

    public void compile(MethodVisitor mv, StackHeights currentStackHeights, StackHeights maxStackHeights) throws CompileException
    {
        if (alias != null) {
            alias.compile(mv, currentStackHeights, maxStackHeights);
        } else if (isBindVar()) {
            int currentStack = currentStackHeights.stackCount;

            // push the current helper instance i.e. this -- adds 1 to stack height
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            // push the variable name -- adds 1 to stack height
            mv.visitLdcInsn(name);
            // increment stack count
            currentStackHeights.addStackCount(2);
            // compile the rhs expression for the binding -- adds 1 to stack height
            value.compile(mv, currentStackHeights, maxStackHeights);
            // make sure value is boxed if necessary
            if (type.isPrimitive()) {
                compileBox(Type.boxType(type), mv, currentStackHeights, maxStackHeights);
            }
            // compile a setBinding call pops 3 from stack height
            mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, Type.internalName(HelperAdapter.class), "setBinding", "(Ljava/lang/String;Ljava/lang/Object;)V");
            currentStackHeights.addStackCount(-3);

            // check the max height was enough for 3 extra values

            // we needed room for 3 more values on the stack -- make sure we got it
            int maxStack = maxStackHeights.stackCount;
            int overflow = (currentStack + 3) - maxStack;

            if (overflow > 0) {
                maxStackHeights.addStackCount(overflow);
            }
        }
    }

    public String getName()
    {
        return name;
    }

    public Expression getValue()
    {
        if (alias != null) {
            return alias.getValue();
        }
        return value;
    }

    public Expression setValue(Expression value)
    {
        Expression oldValue = this.value;
        this.value = value;

        return oldValue;
    }

    public Type getType()
    {
        if (alias != null) {
            return alias.getType();
        }
        return type;
    }

    public void setType(Type type)
    {
        this.type = type;
    }

    public int getCallArrayIndex()
    {
        if (alias != null) {
            return alias.getCallArrayIndex();
        }
        return callArrayIndex;
    }

    public void setCallArrayIndex(int callArrayIndex)
    {
        this.callArrayIndex = callArrayIndex;
    }

    public int getLocalIndex()
    {
        if (alias != null) {
            return alias.getLocalIndex();
        }
        return localIndex;
    }

    public void setLocalIndex(int localIndex)
    {
        this.localIndex = localIndex;
    }

    public boolean isParam()
    {
        return index > 0;
    }

    public boolean isRecipient()
    {
        return index == 0;
    }

    public boolean isHelper()
    {
        return index == HELPER;
    }

    public boolean isBindVar()
    {
        return index == BIND_VAR;
    }

    public boolean isLocalVar()
    {
        return index == LOCAL_VAR;
    }

    public boolean isReturn()
    {
        return index == RETURN_VAR;
    }

    public boolean isThrowable()
    {
        return index == THROWABLE_VAR;
    }

    public boolean isParamCount()
    {
        return index == PARAM_COUNT_VAR;
    }

    public boolean isParamArray()
    {
        return index == PARAM_ARRAY_VAR;
    }

    public int getIndex()
    {
        return index;
    }

    public String getDescriptor() {
        return descriptor;
    }

    public void setDescriptor(String desc) {
        this.descriptor = desc;
    }

    /**
     * record that this binding occurs on the LHS of an assignment
     */
    public void setUpdated()
    {
        updated = true;
        if (alias != null) {
            alias.setUpdated();
        }
    }

    /**
     * record that this binding occurs on the LHS of an assignment
     */
    public boolean isUpdated()
    {
        return updated;
    }

    public void writeTo(StringWriter stringWriter)
    {
        if (isHelper()) {
            stringWriter.write(name);
        } else if (isParam() || isRecipient()) {
            stringWriter.write(name);
            if (type != null && (type.isDefined() || type.isObject())) {
                stringWriter.write(" : ");
                stringWriter.write(type.getName());
            }
        } else {
            stringWriter.write(name);
            if (type != null && (type.isDefined() || type.isObject())) {
                stringWriter.write(" : ");
                stringWriter.write(type.getName());
            }
        }
        if (value != null) {
            stringWriter.write(" = ");
            value.writeTo(stringWriter);
        }
    }


    public void aliasTo(Binding alias)
    {
        if (this.isLocalVar()) {
            this.alias = alias;
            if (this.updated) {
                alias.updated = true;
            }
        } else {
            System.out.println("Binding : attempt to alias non-local var " + getName() + " to " + alias.getName());
        }
    }

    public boolean isAlias()
    {
        return (alias != null);
    }

    public Binding getAlias()
    {
        return alias;
    }

    // special index values for non-positional parameters

    private final static int HELPER = -1;
    private final static int BIND_VAR = -2;
    private final static int LOCAL_VAR = -3;
    private final static int RETURN_VAR = -4;
    private final static int THROWABLE_VAR = -5;
    private final static int PARAM_COUNT_VAR = -6;
    private final static int PARAM_ARRAY_VAR = -7;

    private String name;
    private String descriptor; // supplied when the binding is for a local var
    private Type type;
    private Expression value;
    // the position index of the trigger method recipient or a trigger method parameter or one of the special index
    // values for other types  of parameters.
    private int index;
    // the offset into the trigger method Object array of the initial value for this parameter
    private int callArrayIndex;
    // the offset into the stack at which a local var is located
    private int localIndex;
    private Binding alias; // aliases $x to $n where x is a method parameter name and n its index in the parameter list
    boolean updated; // records whether this binding occurs on the lhs of an assignment
}
