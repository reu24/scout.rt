/*
 * Copyright (c) 2010, 2025 BSI Business Systems Integration AG
 *
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 */

import {Button, GroupBox, ObjectUuidProvider, ObjectUuidSource, scout, Widget, WidgetModel} from '../../src';
import {SpecObjectUuidProvider} from '../../src/testing';

describe('ObjectUuidProvider', () => {

  describe('createUiId', () => {
    it('has correct prefix and increases with each call', () => {
      const nextIdSeqNo = SpecObjectUuidProvider.getUniqueIdSeqNo() + 1;
      expect(ObjectUuidProvider.createUiId()).toBe(ObjectUuidProvider.UI_ID_PREFIX + nextIdSeqNo);
      expect(SpecObjectUuidProvider.getUniqueIdSeqNo()).toBe(nextIdSeqNo);
    });
  });

  describe('isUiId', () => {
    it('correctly detects UI IDs', () => {
      expect(ObjectUuidProvider.isUiId(ObjectUuidProvider.UI_ID_PREFIX + '1234')).toBeTrue();
      expect(ObjectUuidProvider.isUiId('_ui_1')).toBeTrue();
      expect(ObjectUuidProvider.isUiId('_ui_0')).toBeTrue();
      expect(ObjectUuidProvider.isUiId('_ui_1234567890')).toBeTrue();

      expect(ObjectUuidProvider.isUiId(ObjectUuidProvider.UI_ID_PREFIX)).toBeFalse();
      expect(ObjectUuidProvider.isUiId('_ui.1234567890')).toBeFalse();
      expect(ObjectUuidProvider.isUiId('ui1234567890')).toBeFalse(); // old style
      expect(ObjectUuidProvider.isUiId('1234567890')).toBeFalse();
      expect(ObjectUuidProvider.isUiId(ObjectUuidProvider.UI_ID_PREFIX + '1234a')).toBeFalse();
      expect(ObjectUuidProvider.isUiId(ObjectUuidProvider.UI_ID_PREFIX + '1234_')).toBeFalse();
      expect(ObjectUuidProvider.isUiId(ObjectUuidProvider.UI_ID_PREFIX + '.1234.')).toBeFalse();
      expect(ObjectUuidProvider.isUiId(ObjectUuidProvider.UI_ID_PREFIX + 'a')).toBeFalse();
      expect(ObjectUuidProvider.isUiId(ObjectUuidProvider.UI_ID_PREFIX + '12.34')).toBeFalse();
    });
  });

  describe('isUuidPathSkipWidget', () => {

    class TestGroupBox extends GroupBox {
    }

    it('only matches exact classes and no instanceof', () => {
      expect(ObjectUuidProvider.isUuidPathSkipWidget(null)).toBeTrue(); // skip null objects
      expect(ObjectUuidProvider.isUuidPathSkipWidget(new GroupBox())).toBeTrue();

      expect(ObjectUuidProvider.isUuidPathSkipWidget(new TestGroupBox())).toBeFalse();
      ObjectUuidProvider.UuidPathSkipWidgets.add(TestGroupBox);
      expect(ObjectUuidProvider.isUuidPathSkipWidget(new TestGroupBox())).toBeTrue();
    });

    afterAll(() => {
      ObjectUuidProvider.UuidPathSkipWidgets.delete(TestGroupBox);
    });
  });

  describe('uuid', () => {

    let session: SandboxSession;
    beforeEach(() => {
      setFixtures(sandbox());
      session = sandboxSession();
    });

    describe('fallback', () => {
      class MyObj {
      }

      class MyCustomButton extends Button {
      }

      class MyCustomButtonWithStaticModel extends Button {
        protected override _jsonModel(): WidgetModel {
          return {objectType: Button};
        }
      }

      it('uses id if set', () => {
        assertUuid({id: 'myId', objectType: 'obj'}, 'myId');
      });

      it('uses object type if there is no id', () => {
        assertUuid({objectType: 'obj'}, 'obj');
      });

      it('resolves the object type if objectType is not a string or not set at all', () => {
        assertUuid(new Button(), 'Button'); // No objectType -> will be resolved based on constructor
        assertUuid(scout.create(Button, {parent: session.desktop}), 'Button'); // string objectType available -> no resolving necessary
        // @ts-expect-error
        assertUuid({objectType: Button}, 'Button');
        assertUuid(scout.create(MyCustomButtonWithStaticModel, {parent: session.desktop}), 'Button'); // MyCustomButtonWithStaticModel is not registered but objectType is explicitly set

        // Button is registered but MyCustomButton is not -> Button cannot be resolved
        assertUuid(new MyCustomButton(), null);
        assertUuid(scout.create(MyCustomButton, {parent: session.desktop}), null);
        assertUuid(scout.create({parent: session.desktop, objectType: MyCustomButton}), null);

        // Object is not registered and cannot be resolved
        assertUuid(new MyObj(), null);
        assertUuid(scout.create(MyObj), null);
        // @ts-expect-error
        assertUuid({objectType: MyObj}, null);
      });

      it('ignores model adapter ids', () => {
        assertUuid({id: '123'}, null); // Model adapter ids are just numbers
        assertUuid({id: '123', objectType: 'Button'}, 'Button'); // considers object type if id is ignored
      });

      it('ignores ui sequence ids', () => {
        assertUuid({id: ObjectUuidProvider.createUiId()}, null);
        assertUuid({id: ObjectUuidProvider.createUiId(), objectType: 'Button'}, 'Button'); // considers object type if id is ignored
      });

      it('ignores temporary id', () => {
        assertUuid({id: ObjectUuidProvider.UI_ID_REQUIRED}, null);
        assertUuid({id: ObjectUuidProvider.UI_ID_REQUIRED, objectType: 'Button'}, 'Button'); // considers object type if id is ignored
      });

      it('ignores object type and id if fallback is disabled', () => {
        assertUuid({id: 'id3', objectType: '4'}, null, false);
        assertUuid({objectType: '4'}, null, false);
        assertUuid({id: 'id3'}, null, false);
      });
    });

    it('prefers classId if set', () => {
      assertUuid({classId: '1', uuid: '2', id: 'id3', objectType: '4'}, '1');
    });

    it('uses uuid if set', () => {
      assertUuid({uuid: '2', id: 'id3', objectType: '4'}, '2');
      assertUuid({}, null);
    });

    it('prefers uuid over fallback properties', () => {
      assertUuid({uuid: '2', id: 'myId', objectType: 'Button'}, '2');
    });

    function assertUuid(object: ObjectUuidSource, expectedUuid: string, useFallback?: boolean) {
      expect(ObjectUuidProvider.get().uuid(object, useFallback)).toBe(expectedUuid);
    }
  });

  describe('uuidPath', () => {

    let session: SandboxSession;
    beforeEach(() => {
      setFixtures(sandbox());
      session = sandboxSession();
    });

    it('uses uuid if no parent present', () => {
      assertUuidPath({uuid: '1'}, '1');
      assertUuidPath({id: 'myId'}, 'myId');
      assertUuidPath({classId: '3'}, '3');
    });

    it('returns null if object has no id', () => {
      assertUuidPath({}, null);
    });

    it('uses parent if not in skip list', () => {
      session.desktop.id = '1'; // ensure desktop has an id. Should be ignored for uuidPath.
      const parent = scout.create(Widget, {parent: session.desktop, id: 'id3'});
      const object = {
        uuid: '4',
        parent
      };
      assertUuidPath(object, '4|id3');

      const root = scout.create(Widget, {parent: session.desktop, id: 'id2'});
      const group = scout.create(GroupBox, {parent: root, uuid: '3' /* must be ignored */});
      const object2 = {
        uuid: '4',
        parent: group
      };
      assertUuidPath(object2, '4|id2');
    });

    it('returns null if object has no uuid candidates', () => {
      const parent = scout.create(Widget, {parent: session.desktop, id: 'id3'});
      const object = {
        id: ObjectUuidProvider.createUiId(),
        parent
      };
      assertUuidPath(object, null);
    });

    it('works recursively', () => {
      const root = scout.create(Widget, {parent: session.desktop, id: 'id2'});
      const group = scout.create(Widget, {parent: root, uuid: '3'});
      const parent = scout.create(Widget, {parent: group});
      const object = {
        uuid: '4',
        parent: parent
      };
      assertUuidPath(object, '4|Widget|3|id2');
    });

    it('ignores parents without uuid and classId if fallback is disabled', () => {
      const root = scout.create(Widget, {parent: session.desktop, uuid: '2'});
      const group = scout.create(Widget, {parent: root, id: 'id3'});
      const parent = scout.create(Widget, {parent: group});
      const object = {
        uuid: '4',
        parent: parent
      };
      assertUuidPath(object, '4|2', false);
    });

    it('skips parent if requested', () => {
      const root = scout.create(Widget, {parent: session.desktop, uuid: '2' /* skipped by request */});
      const group = scout.create(Widget, {parent: root, uuid: '3'});
      assertUuidPath(group, '3', false, false);
    });

    it('stops on classId by default', () => {
      const root = scout.create(Widget, {parent: session.desktop, id: 'id2' /* ignored because child uses classId which stops the parent visit */});
      const group = scout.create(Widget, {parent: root, classId: '3'});
      const object = {
        classId: '4',
        parent: group
      };
      assertUuidPath(object, '4');
      assertUuidPath(object, '4|3', true, true /* enforce to use parent even if a classId is present */);
    });

    it('ignores parent if classId from remote is used', () => {
      const remoteParent = {
        id: 'id1',
        classId: '2'
      } as Widget;
      const remoteElement = {
        classId: '3',
        parent: remoteParent // should not be used for classId
      };
      assertUuidPath(remoteElement, '3');
    });

    function assertUuidPath(object: ObjectUuidSource, expectedUuidPath: string, useFallback?: boolean, appendParent?: boolean) {
      expect(ObjectUuidProvider.get().uuidPath(object, {useFallback, appendParent})).toBe(expectedUuidPath);
    }
  });
});
