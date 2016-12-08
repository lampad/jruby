/*
 **** BEGIN LICENSE BLOCK *****
 * Version: EPL 1.0/GPL 2.0/LGPL 2.1
 *
 * The contents of this file are subject to the Eclipse Public
 * License Version 1.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of
 * the License at http://www.eclipse.org/legal/epl-v10.html
 *
 * Software distributed under the License is distributed on an "AS
 * IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * rights and limitations under the License.
 *
 * Copyright (C) 2016 Karol Bucek
 *
 * Alternatively, the contents of this file may be used under the terms of
 * either of the GNU General Public License Version 2 or later (the "GPL"),
 * or the GNU Lesser General Public License Version 2.1 or later (the "LGPL"),
 * in which case the provisions of the GPL or the LGPL are applicable instead
 * of those above. If you wish to allow use of your version of this file only
 * under the terms of either the GPL or the LGPL, and not to allow others to
 * use your version of this file under the terms of the EPL, indicate your
 * decision by deleting the provisions above and replace them with the notice
 * and other provisions required by the GPL or the LGPL. If you do not delete
 * the provisions above, a recipient may use your version of this file under
 * the terms of any one of the EPL, the GPL or the LGPL.
 ***** END LICENSE BLOCK *****/
package org.jruby.ext.set;

import org.jcodings.specific.USASCIIEncoding;
import org.jruby.*;
import org.jruby.anno.JRubyMethod;
import org.jruby.common.IRubyWarnings;
import org.jruby.runtime.*;
import org.jruby.runtime.builtin.IRubyObject;
import org.jruby.util.ArraySupport;

import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Set;

import static org.jruby.RubyEnumerator.enumeratorizeWithSize;

/**
 * Native implementation of Ruby's Set (set.rb replacement).
 *
 * @author kares
 */
@org.jruby.anno.JRubyClass(name="Set", include = { "Enumerable" })
public class RubySet extends RubyObject { // implements Set {

    static RubyClass createSetClass(final Ruby runtime) {
        RubyClass Set = runtime.defineClass("Set", runtime.getObject(), ALLOCATOR);

        Set.setReifiedClass(RubySet.class);

        Set.includeModule(runtime.getEnumerable());
        Set.defineAnnotatedMethods(RubySet.class);

        runtime.getLoadService().require("jruby/set.rb");

        return Set;
    }

    private static final ObjectAllocator ALLOCATOR = new ObjectAllocator() {
        public RubySet allocate(Ruby runtime, RubyClass klass) {
            return new RubySet(runtime, klass);
        }
    };

    RubyHash hash; // @hash

    protected RubySet(Ruby runtime, RubyClass klass) {
        super(runtime, klass);
    }

    /*
    private RubySet(Ruby runtime, RubyHash hash) {
        super(runtime, runtime.getClass("Set"));
        initHash(hash);
    } */

    final void initHash(final Ruby runtime) {
        initHash(new RubyHash(runtime));
    }

    final void initHash(final Ruby runtime, final int size) {
        initHash(new RubyHash(runtime, size));
    }

    final void initHash(final RubyHash hash) {
        this.hash = hash;
        setInstanceVariable("@hash", hash); // MRI compat with set.rb
    }

    RubySet newSet(final Ruby runtime) {
        RubySet set = new RubySet(runtime, getMetaClass());
        set.initHash(runtime);
        return set;
    }

    private RubySet newSet(final ThreadContext context, final RubyClass metaClass, final RubyArray elements) {
        RubySet set = new RubySet(context.runtime, metaClass);
        final int len = elements.size();
        set.initHash(context.runtime, len);
        for ( int i = 0; i < len; i++ ) {
            set.invokeAdd(context, elements.eltInternal(i));
        }
        return set;
    }

    /**
     * Creates a new set containing the given objects.
     */
    @JRubyMethod(name = "[]", rest = true, meta = true) // def self.[](*ary)
    public static RubySet create(final ThreadContext context, IRubyObject self, IRubyObject... ary) {
        final Ruby runtime = context.runtime;

        RubySet set = new RubySet(runtime, (RubyClass) self);
        set.initHash(runtime, Math.max(4, ary.length));
        for ( int i=0; i<ary.length; i++ ) set.invokeAdd(context, ary[i]);
        return set;
    }

    /**
     * initialize(enum = nil, &block)
     */
    @JRubyMethod(visibility = Visibility.PRIVATE) // def initialize(enum = nil, &block)
    public IRubyObject initialize(ThreadContext context, Block block) {
        if ( block.isGiven() && context.runtime.isVerbose() ) {
            context.runtime.getWarnings().warning(IRubyWarnings.ID.BLOCK_UNUSED, "given block not used");
        }
        initHash(context.runtime);
        return this;
    }

    /**
     * initialize(enum = nil, &block)
     */
    @JRubyMethod(required = 1, visibility = Visibility.PRIVATE)
    public IRubyObject initialize(ThreadContext context, IRubyObject enume, Block block) {
        if ( enume.isNil() ) return initialize(context, block);

        if ( block.isGiven() ) {
            return initWithEnum(context, enume, block);
        }

        initHash(context.runtime);
        return callMethod(context, "merge", enume); // TODO site-cache
    }

    protected IRubyObject initialize(ThreadContext context, IRubyObject[] args, Block block) {
        switch (args.length) {
            case 0: return initialize(context, block);
            case 1: return initialize(context, args[0], block);
        }
        throw context.runtime.newArgumentError(args.length, 1);
    }

    private IRubyObject initWithEnum(final ThreadContext context, final IRubyObject enume, final Block block) {
        if ( enume instanceof RubyArray ) {
            RubyArray ary = (RubyArray) enume;
            initHash(context.runtime, ary.size());
            for ( int i = 0; i < ary.size(); i++ ) {
                invokeAdd(context, block.yield(context, ary.eltInternal(i)));
            }
            return ary; // done
        }

        if ( enume instanceof RubySet ) {
            RubySet set = (RubySet) enume;
            initHash(context.runtime, set.size());
            for ( IRubyObject elem : set.elementsOrdered() ) {
                invokeAdd(context, block.yield(context, elem));
            }
            return set; // done
        }

        final Ruby runtime = context.runtime;

        initHash(runtime);

        // set.rb do_with_enum :
        return doWithEnum(context, enume, new EachBody(runtime) {
            IRubyObject yieldImpl(ThreadContext context, IRubyObject val) {
                return invokeAdd(context, block.yield(context, val));
            }
        });
    }

    // set.rb do_with_enum (block is required)
    private static IRubyObject doWithEnum(final ThreadContext context, final IRubyObject enume, final EachBody blockImpl) {
        if ( enume.respondsTo("each_entry") ) {
            return enume.callMethod(context, "each_entry", IRubyObject.NULL_ARRAY, new Block(blockImpl));
        }
        if ( enume.respondsTo("each") ) {
            return enume.callMethod(context, "each", IRubyObject.NULL_ARRAY, new Block(blockImpl));
        }

        throw context.runtime.newArgumentError("value must be enumerable");
    }

    private IRubyObject invokeAdd(final ThreadContext context, final IRubyObject val) {
        return this.callMethod(context,"add", val); // TODO site-cache
    }

    private static abstract class EachBody extends JavaInternalBlockBody {

        EachBody(final Ruby runtime) {
            super(runtime, Signature.ONE_REQUIRED);
        }

        @Override
        public IRubyObject yield(ThreadContext context, IRubyObject[] args) {
            return yieldImpl(context, args[0]);
        }

        abstract IRubyObject yieldImpl(ThreadContext context, IRubyObject val) ;

        @Override
        protected final IRubyObject doYield(ThreadContext context, Block block, IRubyObject[] args, IRubyObject self) {
            return yieldImpl(context, args[0]);
        }

        @Override
        protected final IRubyObject doYield(ThreadContext context, Block block, IRubyObject value) {
            return yieldImpl(context, value); // avoid new IRubyObject[] { value }
        }

    }

    @JRubyMethod
    public IRubyObject initialize_dup(ThreadContext context, IRubyObject orig) {
        super.initialize_copy(orig);
        initHash((RubyHash) (((RubySet) orig).hash).dup(context));
        return this;
    }

    @JRubyMethod
    public IRubyObject initialize_clone(ThreadContext context, IRubyObject orig) {
        super.initialize_copy(orig);
        initHash((RubyHash) (((RubySet) orig).hash).rbClone(context));
        return this;
    }

    @Override
    @JRubyMethod
    public IRubyObject freeze(ThreadContext context) {
        final RubyHash hash = this.hash;
        if ( hash != null ) hash.freeze(context);
        return super.freeze(context);
    }

    @Override
    @JRubyMethod
    public IRubyObject taint(ThreadContext context) {
        final RubyHash hash = this.hash;
        if ( hash != null ) hash.taint(context);
        return super.taint(context);
    }

    @Override
    @JRubyMethod
    public IRubyObject untaint(ThreadContext context) {
        final RubyHash hash = this.hash;
        if ( hash != null ) hash.untaint(context);
        return super.untaint(context);
    }

    public int size() { return hash.size(); }

    @JRubyMethod(name = "size", alias = "length")
    public IRubyObject length(ThreadContext context) {
        return context.runtime.newFixnum( size() );
    }

    public boolean isEmpty() { return hash.isEmpty(); }

    @JRubyMethod(name = "empty?")
    public IRubyObject empty_p(ThreadContext context) {
        return context.runtime.newBoolean( isEmpty() );
    }

    public void clear() { clearImpl(); }

    @JRubyMethod(name = "clear")
    public IRubyObject rb_clear(ThreadContext context) {
        modifyCheck(context.runtime);

        clearImpl();
        return this;
    }

    protected void clearImpl() {
        hash.rb_clear();
    }

    /**
     * Replaces the contents of the set with the contents of the given enumerable object and returns self.
     */
    @JRubyMethod
    public RubySet replace(final ThreadContext context, IRubyObject enume) {
        if ( enume instanceof RubySet ) {
            modifyCheck(context.runtime);
            clearImpl();
            addImplSet(context, (RubySet) enume);
        }
        else {
            final Ruby runtime = context.runtime;
            // do_with_enum(enum)  # make sure enum is enumerable before calling clear :
            if ( ! enume.getMetaClass().hasModuleInHierarchy(runtime.getEnumerable()) ) {
                // NOTE: likely no need to do this but due MRI compat (do_with_enum) :
                if ( ! enume.respondsTo("each_entry") ) {
                    throw runtime.newArgumentError("value must be enumerable");
                }
            }
            clearImpl();
            rb_merge(context, enume);
        }

        return this;
    }

    /**
     * Converts the set to an array.  The order of elements is uncertain.
     */
    @JRubyMethod
    public RubyArray to_a(final ThreadContext context) {
        // except MRI relies on Hash order so we do as well
        return this.hash.keys();
    }

    // Returns self if no arguments are given.
    @JRubyMethod
    public RubySet to_set(final ThreadContext context, final Block block) {
        if ( block.isGiven() ) {
            RubySet set = new RubySet(context.runtime, getMetaClass());
            set.initialize(context, this, block);
            return set;
        }
        return this;
    }

    // Otherwise, converts the set to another with klass.new(self, *args, &block).
    @JRubyMethod(rest = true)
    public RubySet to_set(final ThreadContext context, final IRubyObject[] args, final Block block) {
        if ( args.length == 0 ) return to_set(context, block);

        final Ruby runtime = context.runtime;

        IRubyObject klass = args[0]; final RubyClass Set = runtime.getClass("Set");

        if ( klass == Set && args.length == 1 & ! block.isGiven() ) {
            return this;
        }

        final IRubyObject[] rest;
        if ( klass instanceof RubyClass ) {
            rest = ArraySupport.newCopy(args, 1, args.length - 1);
        }
        else {
            klass = Set; rest = args;
        }

        RubySet set = new RubySet(context.runtime, (RubyClass) klass);
        set.initialize(context, rest, block);
        return set;
    }

    @JRubyMethod(visibility = Visibility.PROTECTED)
    public RubySet flatten_merge(final ThreadContext context, IRubyObject set) {
        flattenMerge(context, set, new IdentityHashMap());
        return this;
    }

    private void flattenMerge(final ThreadContext context, final IRubyObject set, final IdentityHashMap seen) {
        if ( set instanceof RubySet ) {
            for ( IRubyObject e : ((RubySet) set).elementsOrdered() ) {
                addFlattened(context, seen, e);
            }
        }
        else {
            set.callMethod(context, "each", IRubyObject.NULL_ARRAY, new Block(
                new EachBody(context.runtime) {
                    IRubyObject yieldImpl(ThreadContext context, IRubyObject e) {
                        addFlattened(context, seen, e); return context.nil;
                    }
                })
            );
        }
    }

    private void addFlattened(final ThreadContext context, final IdentityHashMap seen, IRubyObject e) {
        if ( e instanceof RubySet ) {
            if ( seen.containsKey(e) ) {
                throw context.runtime.newArgumentError("tried to flatten recursive Set");
            }
            seen.put(e, null);
            flattenMerge(context, e, seen);
            seen.remove(e);
        }
        else {
            add(context, e); // self.add(e)
        }
    }

    // Returns a new set that is a copy of the set, flattening each containing set recursively.
    @JRubyMethod
    public RubySet flatten(final ThreadContext context) {
        return newSet(context.runtime).flatten_merge(context, this);
    }

    @JRubyMethod(name = "flatten!")
    public IRubyObject flatten_bang(final ThreadContext context) {
        for ( IRubyObject e : elementsOrdered() ) {
            if ( e instanceof RubySet ) { // needs flatten
                return replace(context, flatten(context));
            }
        }
        return context.nil;
    }

    /**
     * Returns true if the set contains the given object.
     */
    @JRubyMethod(name = "include?", alias = { "member?" })
    public RubyBoolean include_p(final ThreadContext context, IRubyObject obj) {
        return hash.has_key_p(context, obj);
    }

    final boolean contains(final ThreadContext context, IRubyObject obj) {
        return include_p(context, obj) == context.runtime.getTrue();
    }

    private boolean allElementsIncluded(ThreadContext context, final RubySet set) {
        for ( IRubyObject o : set.elements() ) { // set.all? { |o| include?(o) }
            if ( ! contains(context, o) ) return false;
        }
        return true;
    }

    // Returns true if the set is a superset of the given set.
    @JRubyMethod(name = "superset?", alias = { ">=" })
    public IRubyObject superset_p(final ThreadContext context, IRubyObject set) {
        if ( set instanceof RubySet ) {
            if ( getMetaClass().isInstance(set) ) {
                return this.hash.op_ge(context, ((RubySet) set).hash);
            }
            // size >= set.size && set.all? { |o| include?(o) }
            return context.runtime.newBoolean(
                    size() >= ((RubySet) set).size() && allElementsIncluded(context, (RubySet) set)
            );
        }
        throw context.runtime.newArgumentError("value must be a set");
    }

    // Returns true if the set is a proper superset of the given set.
    @JRubyMethod(name = "proper_superset?", alias = { ">" })
    public IRubyObject proper_superset_p(final ThreadContext context, IRubyObject set) {
        if ( set instanceof RubySet ) {
            if ( getMetaClass().isInstance(set) ) {
                return this.hash.op_gt(context, ((RubySet) set).hash);
            }
            // size >= set.size && set.all? { |o| include?(o) }
            return context.runtime.newBoolean(
                    size() > ((RubySet) set).size() && allElementsIncluded(context, (RubySet) set)
            );
        }
        throw context.runtime.newArgumentError("value must be a set");
    }

    @JRubyMethod(name = "subset?", alias = { "<=" })
    public IRubyObject subset_p(final ThreadContext context, IRubyObject set) {
        if ( set instanceof RubySet ) {
            if ( getMetaClass().isInstance(set) ) {
                return this.hash.op_le(context, ((RubySet) set).hash);
            }
            // size >= set.size && set.all? { |o| include?(o) }
            return context.runtime.newBoolean(
                    size() <= ((RubySet) set).size() && allElementsIncluded(context, (RubySet) set)
            );
        }
        throw context.runtime.newArgumentError("value must be a set");
    }

    @JRubyMethod(name = "proper_subset?", alias = { "<" })
    public IRubyObject proper_subset_p(final ThreadContext context, IRubyObject set) {
        if ( set instanceof RubySet ) {
            if ( getMetaClass().isInstance(set) ) {
                return this.hash.op_lt(context, ((RubySet) set).hash);
            }
            // size >= set.size && set.all? { |o| include?(o) }
            return context.runtime.newBoolean(
                    size() < ((RubySet) set).size() && allElementsIncluded(context, (RubySet) set)
            );
        }
        throw context.runtime.newArgumentError("value must be a set");
    }

    /**
     * Returns true if the set and the given set have at least one element in common.
     */
    @JRubyMethod(name = "intersect?")
    public IRubyObject intersect_p(final ThreadContext context, IRubyObject set) {
        if ( set instanceof RubySet ) {
            return context.runtime.newBoolean( intersect(context, (RubySet) set) );
        }
        throw context.runtime.newArgumentError("value must be a set");
    }

    public boolean intersect(final ThreadContext context, final RubySet set) {
        if ( size() < set.size() ) {
            // any? { |o| set.include?(o) }
            for ( IRubyObject o : elementsOrdered() ) {
                if ( set.contains(context, o) ) return true;
            }
        }
        else {
            // set.any? { |o| include?(o) }
            for ( IRubyObject o : set.elementsOrdered() ) {
                if ( contains(context, o) ) return true;
            }
        }
        return false;
    }

    /**
     * Returns true if the set and the given set have no element in common.
     * This method is the opposite of +intersect?+.
     */
    @JRubyMethod(name = "disjoint?")
    public IRubyObject disjoint_p(final ThreadContext context, IRubyObject set) {
        if ( set instanceof RubySet ) {
            return context.runtime.newBoolean( ! intersect(context, (RubySet) set) );
        }
        throw context.runtime.newArgumentError("value must be a set");
    }

    @JRubyMethod
    public IRubyObject each(final ThreadContext context, Block block) {
        if ( ! block.isGiven() ) {
            return enumeratorizeWithSize(context, this, "each", enumSize());
        }

        for (IRubyObject elem : elementsOrdered()) block.yield(context, elem);
        return this;
    }

    private RubyEnumerator.SizeFn enumSize() {
        return new RubyEnumerator.SizeFn() {
            @Override
            public IRubyObject size(IRubyObject[] args) {
                return getRuntime().newFixnum( RubySet.this.size() );
            }
        };
    }

    /**
     * Adds the given object to the set and returns self.
     */
    @JRubyMethod(name = "add", alias = "<<")
    public RubySet add(final ThreadContext context, IRubyObject obj) {
        modifyCheck(context.runtime);
        addImpl(context.runtime, obj);
        return this;
    }

    protected void addImpl(final Ruby runtime, final IRubyObject obj) {
        hash.fastASetCheckString(runtime, obj, runtime.getTrue()); // @hash[obj] = true
    }

    protected void addImplSet(final ThreadContext context, final RubySet set) {
        // NOTE: MRI cheats - does not call Set#add thus we do not care ...
        hash.merge_bang(context, set.hash, Block.NULL_BLOCK);
    }

    /**
     * Adds the given object to the set and returns self.  If the object is already in the set, returns nil.
     */
    @JRubyMethod(name = "add?")
    public IRubyObject add_p(final ThreadContext context, IRubyObject obj) {
        // add(o) unless include?(o)
        if ( contains(context, obj) ) return context.nil;
        return add(context, obj);
    }

    @JRubyMethod
    public IRubyObject delete(final ThreadContext context, IRubyObject obj) {
        modifyCheck(context.runtime);
        deleteImpl(obj);
        return this;
    }

    protected boolean deleteImpl(final IRubyObject obj) {
        hash.modify();
        return hash.fastDelete(obj);
    }

    protected void deleteImplIterator(final IRubyObject obj, final Iterator it) {
        it.remove();
    }

    /**
     * Deletes the given object from the set and returns self.  If the object is not in the set, returns nil.
     */
    @JRubyMethod(name = "delete?")
    public IRubyObject delete_p(final ThreadContext context, IRubyObject obj) {
        // delete(o) if include?(o)
        if ( ! contains(context, obj) ) return context.nil;
        return delete(context, obj);
    }

    @JRubyMethod
    public IRubyObject delete_if(final ThreadContext context, Block block) {
        if ( ! block.isGiven() ) {
            return enumeratorizeWithSize(context, this, "delete_if", enumSize());
        }

        Iterator<IRubyObject> it = elements().iterator();
        while ( it.hasNext() ) {
            IRubyObject elem = it.next();
            if ( block.yield(context, elem).isTrue() ) deleteImplIterator(elem, it); // it.remove
        }
        return this;
    }

    @JRubyMethod
    public IRubyObject keep_if(final ThreadContext context, Block block) {
        if ( ! block.isGiven() ) {
            return enumeratorizeWithSize(context, this, "keep_if", enumSize());
        }

        Iterator<IRubyObject> it = elements().iterator();
        while ( it.hasNext() ) {
            IRubyObject elem = it.next();
            if ( ! block.yield(context, elem).isTrue() ) deleteImplIterator(elem, it); // it.remove
        }
        return this;
    }

    @JRubyMethod(name = "collect!", alias = "map!")
    public IRubyObject collect_bang(final ThreadContext context, Block block) {
        if ( ! block.isGiven() ) {
            return enumeratorizeWithSize(context, this, "collect!", enumSize());
        }

        final RubyArray elems = to_a(context); clearImpl();
        for ( int i=0; i<elems.size(); i++ ) {
            addImpl(context.runtime, block.yield(context, elems.eltInternal(i)));
        }
        return this;
    }

    // Equivalent to Set#delete_if, but returns nil if no changes were made.
    @JRubyMethod(name = "reject!")
    public IRubyObject reject_bang(final ThreadContext context, Block block) {
        if ( ! block.isGiven() ) {
            return enumeratorizeWithSize(context, this, "reject!", enumSize());
        }

        final int size = size();
        Iterator<IRubyObject> it = elements().iterator();
        while ( it.hasNext() ) {
            IRubyObject elem = it.next();
            if ( block.yield(context, elem).isTrue() ) deleteImplIterator(elem, it); // it.remove
        }
        return size == size() ? context.nil : this;
    }

    // Equivalent to Set#keep_if, but returns nil if no changes were made.
    @JRubyMethod(name = "select!")
    public IRubyObject select_bang(final ThreadContext context, Block block) {
        if ( ! block.isGiven() ) {
            return enumeratorizeWithSize(context, this, "select!", enumSize());
        }

        final int size = size();
        Iterator<IRubyObject> it = elements().iterator();
        while ( it.hasNext() ) {
            IRubyObject elem = it.next();
            if ( ! block.yield(context, elem).isTrue() ) deleteImplIterator(elem, it); // it.remove
        }
        return size == size() ? context.nil : this;
    }

    /**
     * Merges the elements of the given enumerable object to the set and returns self.
     */
    @JRubyMethod(name = "merge")
    public RubySet rb_merge(final ThreadContext context, IRubyObject enume) {
        final Ruby runtime = context.runtime;

        if ( enume instanceof RubySet ) {
            modifyCheck(runtime);
            addImplSet(context, (RubySet) enume);
        }
        else if ( enume instanceof RubyArray ) {
            modifyCheck(runtime);
            RubyArray ary = (RubyArray) enume;
            for ( int i = 0; i < ary.size(); i++ ) {
                addImpl(runtime, ary.eltInternal(i));
            }
        }
        else { // do_with_enum(enum) { |o| add(o) }
            doWithEnum(context, enume, new EachBody(runtime) {
                IRubyObject yieldImpl(ThreadContext context, IRubyObject val) {
                    addImpl(context.runtime, val); return context.nil;
                }
            });
        }

        return this;
    }

    /**
     * Deletes every element that appears in the given enumerable object and returns self.
     */
    @JRubyMethod(name = "subtract")
    public IRubyObject subtract(final ThreadContext context, IRubyObject enume) {
        final Ruby runtime = context.runtime;

        if ( enume instanceof RubySet ) {
            modifyCheck(runtime);
            for ( IRubyObject elem : ((RubySet) enume).elementsOrdered() ) {
                deleteImpl(elem);
            }
        }
        else if ( enume instanceof RubyArray ) {
            modifyCheck(runtime);
            RubyArray ary = (RubyArray) enume;
            for ( int i = 0; i < ary.size(); i++ ) {
                deleteImpl(ary.eltInternal(i));
            }
        }
        else { // do_with_enum(enum) { |o| delete(o) }
            doWithEnum(context, enume, new EachBody(runtime) {
                IRubyObject yieldImpl(ThreadContext context, IRubyObject val) {
                    deleteImpl(val); return context.nil;
                }
            });
        }

        return this;
    }

    /**
     * Returns a new set built by merging the set and the elements of the given enumerable object.
     */
    @JRubyMethod(name = "|", alias = { "+", "union" })
    public IRubyObject op_or(final ThreadContext context, IRubyObject enume) {
        return ((RubySet) dup()).rb_merge(context, enume); // dup.merge(enum)
    }

    /**
     * Returns a new set built by duplicating the set, removing every element that appears in the given enumerable object.
     */
    @JRubyMethod(name = "-", alias = { "difference" })
    public IRubyObject op_diff(final ThreadContext context, IRubyObject enume) {
        return ((RubySet) dup()).subtract(context, enume);
    }

    /**
     * Returns a new set built by merging the set and the elements of the given enumerable object.
     */
    @JRubyMethod(name = "&", alias = { "intersection" })
    public IRubyObject op_and(final ThreadContext context, IRubyObject enume) {
        final Ruby runtime = context.runtime;

        final RubySet newSet = new RubySet(runtime, getMetaClass());
        if ( enume instanceof RubySet ) {
            newSet.initHash(runtime, ((RubySet) enume).size());
            for ( IRubyObject obj : ((RubySet) enume).elementsOrdered() ) {
                if ( contains(context, obj) ) newSet.addImpl(runtime, obj);
            }
        }
        else if ( enume instanceof RubyArray ) {
            RubyArray ary = (RubyArray) enume;
            newSet.initHash(runtime, ary.size());
            for ( int i = 0; i < ary.size(); i++ ) {
                final IRubyObject obj = ary.eltInternal(i);
                if ( contains(context, obj) ) newSet.addImpl(runtime, obj);
            }
        }
        else {
            newSet.initHash(runtime);
            // do_with_enum(enum) { |o| newSet.add(o) if include?(o) }
            doWithEnum(context, enume, new EachBody(runtime) {
                IRubyObject yieldImpl(ThreadContext context, IRubyObject obj) {
                    if ( contains(context, obj) ) newSet.addImpl(runtime, obj);
                    return context.nil;
                }
            });
        }

        return newSet;
    }

    /**
     * Returns a new set containing elements exclusive between the set and the given enumerable object.
     * `(set ^ enum)` is equivalent to `((set | enum) - (set & enum))`.
     */
    @JRubyMethod(name = "^")
    public IRubyObject op_xor(final ThreadContext context, IRubyObject enume) {
        final Ruby runtime = context.runtime;

        RubySet newSet = new RubySet(runtime, runtime.getClass("Set"));
        newSet.initialize(context, enume, Block.NULL_BLOCK); // Set.new(enum)
        for ( IRubyObject o : elementsOrdered() ) {
            if ( newSet.contains(context, o) ) newSet.deleteImpl(o); // exclusive or
            else newSet.addImpl(runtime, o);
        }

        return newSet;
    }

    @Override
    @JRubyMethod(name = "==")
    public IRubyObject op_equal(ThreadContext context, IRubyObject other) {
        if ( this == other ) return context.runtime.getTrue();
        if ( getMetaClass().isInstance(other) ) {
            return this.hash.op_equal(context, ((RubySet) other).hash); // @hash == ...
        }
        if ( other instanceof RubySet ) {
            RubySet that = (RubySet) other;
            if ( this.size() == that.size() ) { // && includes all of our elements :
                for ( IRubyObject obj : elementsOrdered() ) {
                    if ( ! that.contains(context, obj) ) return context.runtime.getFalse();
                }
                return context.runtime.getTrue();
            }
        }
        return context.runtime.getFalse();
    }

    // TODO Java (Collection) equals !

    @JRubyMethod(name = "eql?")
    public IRubyObject op_eql(ThreadContext context, IRubyObject other) {
        if ( other instanceof RubySet ) {
            return this.hash.op_eql(context, ((RubySet) other).hash);
        }
        return context.runtime.getFalse();
    }

    @Override
    public boolean eql(IRubyObject other) {
        if ( other instanceof RubySet ) {
            final Ruby runtime = getRuntime();
            return this.hash.op_eql(runtime.getCurrentContext(), ((RubySet) other).hash) == runtime.getTrue();
        }
        return false;
    }

    @Override
    @JRubyMethod
    public RubyFixnum hash() { // @hash.hash
        return getRuntime().newFixnum(hashCode());
    }

    @Override
    public int hashCode() {
        return hash.hashCode();
    }


    @JRubyMethod(name = "classify")
    public IRubyObject classify(ThreadContext context, final Block block) {
        if ( ! block.isGiven() ) {
            return enumeratorizeWithSize(context, this, "classify", enumSize());
        }

        final Ruby runtime = context.runtime;

        final RubyHash h = new RubyHash(runtime, size());

        for ( IRubyObject i : elementsOrdered() ) {
            final IRubyObject key = block.yield(context, i);
            IRubyObject set;
            if ( ( set = h.fastARef(key) ) == null ) {
                h.fastASet(key, set = newSet(runtime));
            }
            ((RubySet) set).invokeAdd(context, i);
        }

        return h;
    }

    /**
      * Divides the set into a set of subsets according to the commonality
      * defined by the given block.
      *
      * If the arity of the block is 2, elements o1 and o2 are in common
      * if block.call(o1, o2) is true.  Otherwise, elements o1 and o2 are
      * in common if block.call(o1) == block.call(o2).
      *
      * e.g.:
      *
      *   require 'set'
      *   numbers = Set[1, 3, 4, 6, 9, 10, 11]
      *   set = numbers.divide { |i,j| (i - j).abs == 1 }
      *   p set     # => #<Set: {#<Set: {1}>,
      *             #            #<Set: {11, 9, 10}>,
      *             #            #<Set: {3, 4}>,
      *             #            #<Set: {6}>}>
      */
    @JRubyMethod(name = "divide")
    public IRubyObject divide(ThreadContext context, final Block block) {
        if ( ! block.isGiven() ) {
            return enumeratorizeWithSize(context, this, "divide", enumSize());
        }

        if ( block.getSignature().arityValue() == 2 ) {
            return divideTSort(context, block);
        }

        final Ruby runtime = context.runtime; // Set.new(classify(&func).values) :

        RubyHash vals = (RubyHash) classify(context, block);
        final RubySet set = new RubySet(runtime, runtime.getClass("Set"));
        set.initHash(runtime, vals.size());
        for ( IRubyObject val : (Collection<IRubyObject>) vals.directValues() ) {
            set.invokeAdd(context, val);
        }
        return set;
    }

    private IRubyObject divideTSort(ThreadContext context, final Block block) {
        final Ruby runtime = context.runtime;

        final RubyHash dig = DivideTSortHash.newInstance(context);

        /*
          each { |u|
            dig[u] = a = []
            each{ |v| func.call(u, v) and a << v }
          }
         */
        for ( IRubyObject u : elementsOrdered() ) {
            RubyArray a;
            dig.fastASet(u, a = runtime.newArray());
            for ( IRubyObject v : elementsOrdered() ) {
                IRubyObject ret = block.call(context, u, v);
                if ( ret.isTrue() ) a.append(v);
            }
        }

        /*
          set = Set.new()
          dig.each_strongly_connected_component { |css|
            set.add(self.class.new(css))
          }
          set
         */
        final RubyClass Set = runtime.getClass("Set");
        final RubySet set = new RubySet(runtime, Set);
        set.initHash(runtime, dig.size());
        dig.callMethod(context, "each_strongly_connected_component", IRubyObject.NULL_ARRAY, new Block(
            new JavaInternalBlockBody(runtime, Signature.ONE_REQUIRED) {
                @Override
                public IRubyObject yield(ThreadContext context, IRubyObject[] args) {
                    return doYield(context, null, args[0]);
                }

                @Override
                protected IRubyObject doYield(ThreadContext context, Block block, IRubyObject css) {
                    // set.add( self.class.new(css) ) :
                    set.addImpl(runtime, RubySet.this.newSet(context, Set, (RubyArray) css));
                    return context.nil;
                }
            })
        );

        return set;
    }

    // NOTE: a replacement for set.rb's eval in Set#divide : `class << dig = {} ...`
    public static final class DivideTSortHash extends RubyHash {

        private static final String NAME = "DivideTSortHash"; // private constant under Set::

        static DivideTSortHash newInstance(final ThreadContext context) {
            final Ruby runtime = context.runtime;

            RubyClass Set = runtime.getClass("Set");
            RubyClass klass = (RubyClass) Set.getConstantAt(NAME, true);
            if (klass == null) { // initialize on-demand when Set#divide is first called
                synchronized (DivideTSortHash.class) {
                    klass = (RubyClass) Set.getConstantAt(NAME, true);
                    if (klass == null) {
                        klass = Set.defineClassUnder(NAME, runtime.getHash(), runtime.getHash().getAllocator());
                        Set.setConstantVisibility(runtime, NAME, true); // private
                        klass.includeModule(getTSort(runtime));
                        klass.defineAnnotatedMethods(DivideTSortHash.class);
                    }
                }
            }
            return new DivideTSortHash(runtime, klass);
        }

        DivideTSortHash(final Ruby runtime, final RubyClass metaClass) {
            super(runtime, metaClass);
        }

        /*
         class << dig = {}         # :nodoc:
           include TSort

           alias tsort_each_node each_key
           def tsort_each_child(node, &block)
             fetch(node).each(&block)
           end
         end
         */

        @JRubyMethod
        public IRubyObject tsort_each_node(ThreadContext context, Block block) {
            return each_key(context, block);
        }

        @JRubyMethod
        public IRubyObject tsort_each_child(ThreadContext context, IRubyObject node, Block block) {
            IRubyObject set = fetch(context, node, Block.NULL_BLOCK);
            if ( set instanceof RubySet ) {
                return ((RubySet) set).each(context, block);
            }
            // some Enumerable (we do not expect this to happen)
            return set.callMethod(context, "each", IRubyObject.NULL_ARRAY, block);
        }

    }

    static RubyModule getTSort(final Ruby runtime) {
        if ( ! runtime.getObject().hasConstant("TSort") ) {
            runtime.getLoadService().require("tsort");
        }
        return runtime.getModule("TSort");
    }

    @Override
    public final IRubyObject inspect() {
        return inspect(getRuntime().getCurrentContext());
    }

    private static final byte[] RECURSIVE_BYTES = new byte[] { '.','.','.' };

    // Returns a string containing a human-readable representation of the set.
    // e.g. "#<Set: {element1, element2, ...}>"
    @JRubyMethod(name = "inspect")
    public RubyString inspect(ThreadContext context) {
        final Ruby runtime = context.runtime;

        final RubyString str;

        if (size() == 0) {
            str = RubyString.newStringLight(runtime, 16, USASCIIEncoding.INSTANCE);
            inspectPrefix(str, getMetaClass()); str.cat('{').cat('}').cat('>'); // "#<Set: {}>"
            return str;
        }

        if (runtime.isInspecting(this)) {
            str = RubyString.newStringLight(runtime, 20, USASCIIEncoding.INSTANCE);
            inspectPrefix(str, getMetaClass());
            str.cat('{').cat(RECURSIVE_BYTES).cat('}').cat('>'); // "#<Set: {...}>"
            return str;
        }

        str = RubyString.newStringLight(runtime, 32, USASCIIEncoding.INSTANCE);
        inspectPrefix(str, getMetaClass());

        try {
            runtime.registerInspecting(this);
            inspectSet(context, str);
            return str.cat('>');
        }
        finally {
            runtime.unregisterInspecting(this);
        }
    }

    private static RubyString inspectPrefix(final RubyString str, final RubyClass metaClass) {
        str.cat('#').cat('<').cat(metaClass.getRealClass().getName().getBytes(RubyEncoding.UTF8));
        str.cat(':').cat(' '); return str;
    }

    private void inspectSet(final ThreadContext context, final RubyString str) {

        str.cat((byte) '{');

        boolean tainted = isTaint(); boolean notFirst = false;

        for ( IRubyObject elem : elementsOrdered() ) {
            final RubyString s = inspect(context, elem);
            if ( s.isTaint() ) tainted = true;
            if ( notFirst ) str.cat((byte) ',').cat((byte) ' ');
            else str.setEncoding( s.getEncoding() ); notFirst = true;
            str.cat19( s );
        }

        str.cat((byte) '}');

        if ( tainted ) str.setTaint(true);
    }

    //

    protected final Set<IRubyObject> elements() {
        return hash.directKeySet(); // Hash view -> no copying
    }

    // NOTE: implementation does not expect to be used for altering contents using iterator
    protected Set<IRubyObject> elementsOrdered() {
        return elements(); // potentially -> to be re-defined by SortedSet
    }

    protected final void modifyCheck(final Ruby runtime) {
        if ((flags & FROZEN_F) != 0) throw runtime.newFrozenError("Set");
    }

}