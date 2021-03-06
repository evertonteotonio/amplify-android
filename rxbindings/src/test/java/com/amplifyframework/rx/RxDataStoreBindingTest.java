/*
 * Copyright 2020 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.amplifyframework.rx;

import android.content.Context;

import com.amplifyframework.AmplifyException;
import com.amplifyframework.core.Action;
import com.amplifyframework.core.Consumer;
import com.amplifyframework.core.async.NoOpCancelable;
import com.amplifyframework.core.category.CategoryInitializationResult;
import com.amplifyframework.core.model.Model;
import com.amplifyframework.datastore.DataStoreCategory;
import com.amplifyframework.datastore.DataStoreCategoryConfiguration;
import com.amplifyframework.datastore.DataStoreException;
import com.amplifyframework.datastore.DataStoreItemChange;
import com.amplifyframework.datastore.DataStoreItemChange.Initiator;
import com.amplifyframework.datastore.DataStoreItemChange.Type;
import com.amplifyframework.datastore.DataStorePlugin;
import com.amplifyframework.testutils.Await;
import com.amplifyframework.testutils.RandomModel;
import com.amplifyframework.testutils.RandomString;

import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

import io.reactivex.observers.TestObserver;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests the {@link RxDataStoreBinding}.
 * The general skeleton for these tests is:
 * 1. Mock the DataStore, to pretend that it calls back an error or result
 * 2. Invoke the Rx Binding on top of it
 * 3. Validate that either the operation completed or returned the failure that the category behavior
 * emitted.
 */
@SuppressWarnings("unchecked") // Mockito.any(Raw.class) etc.
public final class RxDataStoreBindingTest {
    private DataStorePlugin<?> delegate;
    private RxDataStoreCategoryBehavior rxDataStore;

    /**
     * Creates a DataStoreCategory that has a mock plugin backing it.
     * Creates an Rx Binding around this category, for test.
     * @throws AmplifyException On failure to add plugin, init/config the category
     */
    @Before
    public void createBindingInFrontOfMockPlugin() throws AmplifyException {
        this.delegate = mock(DataStorePlugin.class);
        when(delegate.getPluginKey()).thenReturn(RandomString.string());

        final DataStoreCategory dataStoreCategory = new DataStoreCategory();
        dataStoreCategory.addPlugin(delegate);
        dataStoreCategory.configure(new DataStoreCategoryConfiguration(), mock(Context.class));
        Await.<CategoryInitializationResult, AmplifyException>result((onResult, onError) ->
            dataStoreCategory.initialize(mock(Context.class), onResult));

        this.rxDataStore = new RxDataStoreBinding(dataStoreCategory);
    }

    @Test
    public void saveCompletesWhenBehaviorEmitsResult() {
        Model model = RandomModel.model();

        // Arrange: category returns notification of change when save is transacted
        doAnswer(invocation -> {
            // 0 = model, 1 = result consumer, 2 = failure consumer
            final int indexOfModel = 0;
            final int indexOfResultConsumer = 1;
            Model modelFromInvocation = invocation.getArgument(indexOfModel);
            Consumer<DataStoreItemChange<Model>> resultConsumer = invocation.getArgument(indexOfResultConsumer);
            resultConsumer.accept(DataStoreItemChange.builder()
                .uuid(modelFromInvocation.getId())
                .type(Type.SAVE)
                .itemClass(Model.class)
                .initiator(Initiator.LOCAL)
                .item(modelFromInvocation)
                .build());
            return null;
        }).when(delegate)
            .save(eq(model), any(Consumer.class), any(Consumer.class));

        // Act: try to save something.
        TestObserver<Void> observer = rxDataStore.save(model).test();

        // Assert: operation completed
        observer.awaitTerminalEvent();
        observer.assertComplete();

        // Assert: behavior was invoked
        verify(delegate)
            .save(eq(model), any(Consumer.class), any(Consumer.class));
    }

    @Test
    public void saveEmitsErrorWhenBehaviorDoes() {
        Model model = RandomModel.model();

        // Arrange: The underlying category behavior returns an error.
        DataStoreException expectedFailure = new DataStoreException("Expected", "Failure");
        doAnswer(invocation -> {
            int indexOfFailureConsumer = 2; // 0 = model, 1 = result consumer, 2 = failure consumer
            Consumer<DataStoreException> failureConsumer = invocation.getArgument(indexOfFailureConsumer);
            failureConsumer.accept(expectedFailure);
            return null;
        }).when(delegate)
            .save(eq(model), any(Consumer.class), any(Consumer.class));

        // Act: try to save something.
        TestObserver<?> observer = rxDataStore.save(model).test();
        observer.awaitTerminalEvent();
        observer.assertError(expectedFailure);

        verify(delegate)
            .save(eq(model), any(Consumer.class), any(Consumer.class));
    }

    @Test
    public void deleteCompletesWhenBehaviorEmitsResult() {
        Model model = RandomModel.model();

        // Arrange for the category to emit a result when delete() is called.
        doAnswer(invocation -> {
            final int indexOModel = 0;
            final int indexOfResultConsumer = 1;
            Model invokedModel = invocation.getArgument(indexOModel);
            Consumer<DataStoreItemChange<Model>> resultConsumer = invocation.getArgument(indexOfResultConsumer);
            resultConsumer.accept(DataStoreItemChange.builder()
                .uuid(invokedModel.getId())
                .item(invokedModel)
                .initiator(Initiator.LOCAL)
                .type(Type.DELETE)
                .itemClass(Model.class)
                .build());
            return null;
        }).when(delegate)
            .delete(eq(model), any(Consumer.class), any(Consumer.class));

        // Act: okay, call delete() on the Rx binding.
        TestObserver<Void> observer = rxDataStore.delete(model).test();
        observer.awaitTerminalEvent();
        observer.assertComplete();

        verify(delegate)
            .delete(eq(model), any(Consumer.class), any(Consumer.class));
    }

    @Test
    public void deleteEmitsErrorWhenBehaviorDoes() {
        // Arrange: delete() category behavior will callback failure consumer
        Model model = RandomModel.model();
        DataStoreException expectedFailure = new DataStoreException("Expected", "Failure");
        doAnswer(invocation -> {
            final int indexOfFailureConsumer = 2;
            Consumer<DataStoreException> failureConsumer = invocation.getArgument(indexOfFailureConsumer);
            failureConsumer.accept(expectedFailure);
            return null;
        }).when(delegate)
            .delete(eq(model), any(Consumer.class), any(Consumer.class));

        // Act: try to delete a model via the Rx binding
        TestObserver<Void> observer = rxDataStore.delete(model).test();

        // Assert: the same failure bubbled out from the category behavior
        observer.awaitTerminalEvent();
        observer.assertError(expectedFailure);

        verify(delegate)
            .delete(eq(model), any(Consumer.class), any(Consumer.class));
    }

    @Test
    public void queryEmitsCategoryBehaviorResults() {
        // Arrange: query will return some results from category behavior
        List<Model> models = Arrays.asList(RandomModel.model(), RandomModel.model());
        doAnswer(invocation -> {
            final int positionOfResultConsumer = 1; // 0 = clazz, 1 = result consumer, 2 = error consumer
            Consumer<Iterator<Model>> resultConsumer = invocation.getArgument(positionOfResultConsumer);
            resultConsumer.accept(models.iterator());
            return null;
        }).when(delegate)
            .query(eq(Model.class), any(Consumer.class), any(Consumer.class));

        // Act: call Rx Binding to query for Model.class
        TestObserver<Model> observer = rxDataStore.query(Model.class).test();

        // Assert:
        observer.awaitTerminalEvent();
        observer.assertValueSet(models);

        verify(delegate)
            .query(eq(Model.class), any(Consumer.class), any(Consumer.class));
    }

    @Test
    public void queryEmitsFailureWhenCategoryBehaviorDoes() {
        DataStoreException expectedFailure = new DataStoreException("Expected", "Failure");
        doAnswer(invocation -> {
            final int positionOrFailureConsumer = 2;
            Consumer<DataStoreException> failureConsumer = invocation.getArgument(positionOrFailureConsumer);
            failureConsumer.accept(expectedFailure);
            return null;
        }).when(delegate)
            .query(eq(Model.class), any(Consumer.class), any(Consumer.class));

        TestObserver<Model> observer = rxDataStore.query(Model.class).test();
        observer.awaitTerminalEvent();
        observer.assertError(expectedFailure);

        verify(delegate)
            .query(eq(Model.class), any(Consumer.class), any(Consumer.class));
    }

    @Test
    public void observeReturnsCategoryBehaviorChanges() {
        // Arrange: observe(Class<?>) will spit out some values from category behavior.
        Model model = RandomModel.model();
        DataStoreItemChange<Model> changeEvent = DataStoreItemChange.builder()
            .uuid(model.getId())
            .itemClass(Model.class)
            .item(model)
            .type(Type.SAVE)
            .initiator(Initiator.LOCAL)
            .build();
        doAnswer(invocation -> {
            final int positionOfValueConsumer = 1; // 0 = clazz, 1 = item consumer, 2 = failure consumer, 3 = onComplete
            Consumer<DataStoreItemChange<Model>> onNext = invocation.getArgument(positionOfValueConsumer);
            onNext.accept(changeEvent);
            return null;
        }).when(delegate)
            .observe(eq(Model.class), any(Consumer.class), any(Consumer.class), any(Action.class));

        // Act: Observe the DataStore via Rx binding
        TestObserver<DataStoreItemChange<Model>> observer = rxDataStore.observe(Model.class).test();

        // Assert: event is observed
        observer
            .awaitCount(1)
            .assertValue(changeEvent);

        verify(delegate)
            .observe(eq(Model.class), any(Consumer.class), any(Consumer.class), any(Action.class));
    }

    @Test
    public void observeCompletesWhenCategoryBehaviorDoes() {
        // Category behavior is arranged to complete
        doAnswer(invocation -> {
            final int positionOfOnComplete = 3; // 0 = clazz, 1 = onNext, 2 = onFailure, 3 = onComplete
            Action onComplete = invocation.getArgument(positionOfOnComplete);
            onComplete.call();
            return null;
        }).when(delegate)
            .observe(eq(Model.class), any(Consumer.class), any(Consumer.class), any(Action.class));

        // Act: observe via Rx binding
        TestObserver<DataStoreItemChange<Model>> observer = rxDataStore.observe(Model.class).test();
        observer.awaitTerminalEvent();
        observer.assertComplete();

        verify(delegate)
            .observe(eq(Model.class), any(Consumer.class), any(Consumer.class), any(Action.class));
    }

    @Test
    public void observeFailsWhenCategoryBehaviorDoes() {
        // Arrange for observer() to callback failure
        DataStoreException expectedFailure = new DataStoreException("Expected", "Failure");
        doAnswer(invocation -> {
            final int positionOfOnFailure = 2; // 0 = clazz, 1 = onNext, 2 = onFailure, 3 = onComplete
            Consumer<DataStoreException> onFailure = invocation.getArgument(positionOfOnFailure);
            onFailure.accept(expectedFailure);
            return new NoOpCancelable();
        }).when(delegate)
            .observe(eq(Model.class), any(Consumer.class), any(Consumer.class), any(Action.class));

        // Act: observe the DataStore via Rx binding
        TestObserver<DataStoreItemChange<Model>> observer = rxDataStore.observe(Model.class).test();

        // Assert: failure is propagated
        observer.awaitTerminalEvent();
        observer.assertError(expectedFailure);

        verify(delegate)
            .observe(eq(Model.class), any(Consumer.class), any(Consumer.class), any(Action.class));
    }
}
